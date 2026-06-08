package me.rerere.rikkahub.voiceagent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AndroidVoiceAudioEngine(context: Context) : VoiceAudioEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val captureCallbackLock = Any()
    private val captureRecordLock = Any()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playbackWriter = VoicePlaybackWriter(
        scope = scope,
        createSink = ::createAudioTrackSinkOrNull,
        onDiagnostic = ::handlePlaybackDiagnostic,
    )
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var debugCaptureRegistration: VoiceAudioDebugInjector.Registration? = null
    private var hasAudioFocus = false
    private var captureGeneration = 0L
    private var errorHandler: ((String) -> Unit)? = null
    private var released = false

    override fun setErrorHandler(onError: ((String) -> Unit)?) {
        synchronized(lock) {
            errorHandler = onError
        }
    }

    override fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Microphone permission is required")
        }

        stopCapture()
        requestAudioFocusOrThrow()

        val generation = synchronized(lock) {
            check(!released) { "Voice audio engine is released" }
            captureGeneration += 1
            captureGeneration
        }
        val bufferSize = captureBufferSize()
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2,
            )
        }.getOrElse {
            throw IllegalStateException("AudioRecord creation failed", it)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.releaseSafely()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val buffer = ByteArray(bufferSize)
            try {
                captureLoop@ while (isActive && isCurrentCapture(generation, recorder)) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: RuntimeException) {
                        if (isCurrentCapture(generation, recorder)) {
                            Log.w(TAG, "AudioRecord read failed", e)
                            notifyAudioError("AudioRecord read failed: ${e.message ?: e.javaClass.simpleName}")
                        }
                        break@captureLoop
                    }

                    when (read) {
                        in 1..buffer.size -> {
                            deliverCaptureBuffer(generation, recorder, buffer.copyOf(read), onPcm16)
                        }
                        0 -> Unit
                        else -> {
                            if (isCurrentCapture(generation, recorder)) {
                                try {
                                    throw IllegalStateException("AudioRecord read error: $read")
                                } catch (e: IllegalStateException) {
                                    Log.w(TAG, "Stopping capture after AudioRecord read failure", e)
                                    notifyAudioError(e.message ?: e.javaClass.simpleName)
                                }
                            }
                            break@captureLoop
                        }
                    }
                }
            } finally {
                stopAndReleaseRecorder(recorder)
                clearRecorder(recorder)
            }
        }

        var published = false
        synchronized(lock) {
            if (!released && generation == captureGeneration) {
                audioRecord = recorder
                captureJob = job
                published = true
            }
        }

        if (!published || !isCurrentCapture(generation, recorder)) {
            job.cancel()
            releaseRecorder(recorder)
            return
        }

        synchronized(captureRecordLock) {
            if (!isCurrentCapture(generation, recorder)) {
                job.cancel()
                recorder.releaseSafely()
                return
            }

            try {
                recorder.startRecording()
            } catch (e: RuntimeException) {
                val stillCurrent = isCurrentCapture(generation, recorder)
                if (stillCurrent) {
                    clearRecorder(generation, recorder)
                }
                job.cancel()
                recorder.releaseSafely()
                if (stillCurrent) {
                    throw IllegalStateException("AudioRecord start failed", e)
                }
                return
            }

            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val stillCurrent = isCurrentCapture(generation, recorder)
                if (stillCurrent) {
                    clearRecorder(generation, recorder)
                }
                job.cancel()
                recorder.releaseSafely()
                if (stillCurrent) {
                    throw IllegalStateException("AudioRecord start failed")
                }
                return
            }

            if (isCurrentCapture(generation, recorder)) {
                job.start()
                registerDebugCapture(generation, recorder, onPcm16, onDebugInjectionComplete)
            } else {
                job.cancel()
                recorder.stopSafely()
                recorder.releaseSafely()
            }
        }
    }

    override fun stopCapture() {
        val job: Job?
        val recorder: AudioRecord?
        synchronized(lock) {
            captureGeneration += 1
            job = captureJob
            captureJob = null
            recorder = audioRecord
            audioRecord = null
            unregisterDebugCaptureLocked()
        }
        job?.cancel()
        recorder?.let(::stopAndReleaseRecorder)
        waitForCaptureCallbacks()
    }

    override fun playPcm16(base64Pcm16: String) {
        playPcm16(base64Pcm16 = base64Pcm16, sessionId = null)
    }

    override fun playPcm16(base64Pcm16: String, sessionId: Long?) {
        playbackWriter.playBase64(base64Pcm16 = base64Pcm16, sessionId = sessionId)
    }

    override fun activatePlaybackSession(sessionId: Long) {
        playbackWriter.activateSession(sessionId)
    }

    override fun invalidatePlaybackSession() {
        playbackWriter.invalidateSession()
    }

    override fun suppressPlayback() {
        playbackWriter.suppress()
    }

    override fun release() {
        val job: Job?
        val recorder: AudioRecord?
        synchronized(lock) {
            if (released) {
                return
            }
            released = true
            captureGeneration += 1
            job = captureJob
            captureJob = null
            recorder = audioRecord
            audioRecord = null
            unregisterDebugCaptureLocked()
        }
        job?.cancel()
        recorder?.let(::stopAndReleaseRecorder)
        playbackWriter.release()
        abandonAudioFocus()
        waitForCaptureCallbacks()
        scope.cancel()
    }

    private fun clearRecorder(generation: Long, recorder: AudioRecord) {
        synchronized(lock) {
            if (captureGeneration == generation && audioRecord === recorder) {
                captureJob = null
                audioRecord = null
                unregisterDebugCaptureLocked()
            }
        }
    }

    private fun clearRecorder(recorder: AudioRecord) {
        synchronized(lock) {
            if (audioRecord === recorder) {
                captureJob = null
                audioRecord = null
                unregisterDebugCaptureLocked()
            }
        }
    }

    private fun invalidateCapture(generation: Long, recorder: AudioRecord) {
        synchronized(lock) {
            if (captureGeneration == generation && audioRecord === recorder) {
                captureGeneration += 1
            }
        }
    }

    private fun isCurrentCapture(generation: Long, recorder: AudioRecord): Boolean = synchronized(lock) {
        captureGeneration == generation && audioRecord === recorder
    }

    private fun registerDebugCapture(
        generation: Long,
        recorder: AudioRecord,
        onPcm16: (ByteArray) -> Unit,
        onInjectionComplete: () -> Unit,
    ) {
        val registration = VoiceAudioDebugInjector.registerCapture(
            onPcm16 = { buffer ->
                deliverInjectedCaptureBuffer(
                    generation = generation,
                    recorder = recorder,
                    buffer = buffer,
                    onPcm16 = onPcm16,
                )
            },
            onInjectionComplete = {
                synchronized(captureCallbackLock) {
                    if (isCurrentCapture(generation, recorder)) {
                        onInjectionComplete()
                    }
                }
            },
        )
        synchronized(lock) {
            if (captureGeneration == generation && audioRecord === recorder) {
                unregisterDebugCaptureLocked()
                debugCaptureRegistration = registration
            } else {
                registration.close()
            }
        }
    }

    private fun unregisterDebugCaptureLocked() {
        debugCaptureRegistration?.close()
        debugCaptureRegistration = null
    }

    private fun deliverCaptureBuffer(
        generation: Long,
        recorder: AudioRecord,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        synchronized(captureCallbackLock) {
            if (isCurrentCapture(generation, recorder)) {
                try {
                    onPcm16(buffer)
                } catch (e: Exception) {
                    Log.w(TAG, "Stopping capture after PCM callback failure", e)
                    invalidateCapture(generation, recorder)
                }
            }
        }
    }

    private fun deliverInjectedCaptureBuffer(
        generation: Long,
        recorder: AudioRecord,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        synchronized(captureCallbackLock) {
            if (!isCurrentCapture(generation, recorder)) return
            try {
                onPcm16(buffer)
            } catch (e: Exception) {
                Log.w(TAG, "Stopping capture after debug PCM injection callback failure", e)
                invalidateCapture(generation, recorder)
            }
        }
    }

    private fun waitForCaptureCallbacks() {
        synchronized(captureCallbackLock) {
            // Wait for any in-flight capture callback that passed its final generation check.
        }
    }

    private fun stopAndReleaseRecorder(recorder: AudioRecord) {
        synchronized(captureRecordLock) {
            recorder.stopSafely()
            recorder.releaseSafely()
        }
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        synchronized(captureRecordLock) {
            recorder.releaseSafely()
        }
    }

    private fun createAudioTrackSinkOrNull(): VoicePcm16Sink? {
        val track = getOrCreatePlaybackTrack() ?: return null
        return AndroidAudioTrackSink(track)
    }

    private fun getOrCreatePlaybackTrack(): AudioTrack? {
        val existingTrack = synchronized(lock) { audioTrack }
        if (existingTrack != null) {
            return currentPlaybackTrack(existingTrack)
        }

        val newTrack = createAudioTrackOrNull() ?: return null
        var selectedTrack: AudioTrack? = null
        var shouldReleaseNewTrack = false
        synchronized(lock) {
            val currentTrack = audioTrack
            if (released) {
                shouldReleaseNewTrack = true
            } else if (currentTrack == null) {
                audioTrack = newTrack
                selectedTrack = newTrack
            } else {
                selectedTrack = currentTrack
                shouldReleaseNewTrack = true
            }
        }

        if (shouldReleaseNewTrack) {
            newTrack.releaseSafely()
        }
        return currentPlaybackTrack(selectedTrack ?: return null)
    }

    private fun currentPlaybackTrack(track: AudioTrack): AudioTrack? = synchronized(lock) {
        if (!released && audioTrack === track) {
            track
        } else {
            null
        }
    }

    private fun createAudioTrackOrNull(): AudioTrack? {
        val bufferSize = playbackBufferSizeOrNull() ?: return null
        val attributes = voiceAudioAttributes()
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val track = runCatching {
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.onFailure {
            Log.w(TAG, "AudioTrack creation failed", it)
            notifyAudioError("AudioTrack creation failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
            notifyAudioError("AudioTrack initialization failed: state=${track.state}")
            track.releaseSafely()
            return null
        }

        return track
    }

    private fun requestAudioFocusOrThrow() {
        val manager = audioManager ?: return
        synchronized(lock) {
            check(!released) { "Voice audio engine is released" }
            if (hasAudioFocus) return
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(voiceAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange <= 0) {
                        notifyAudioError("Audio focus lost: $focusChange")
                    }
                }
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = null
            throw IllegalStateException("Voice Agent audio focus request failed")
        }
        synchronized(lock) {
            if (released) {
                abandonAudioFocus()
            } else {
                hasAudioFocus = true
            }
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = audioFocusRequest
        audioFocusRequest = null
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            manager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    private fun voiceAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun removeTrack(track: AudioTrack) {
        synchronized(lock) {
            if (audioTrack === track) {
                audioTrack = null
            }
        }
    }

    private fun notifyAudioError(message: String) {
        val handler = synchronized(lock) {
            if (released) null else errorHandler
        }
        handler?.invoke(message)
    }

    private fun AudioTrack.playSafely(): Boolean {
        return runCatching {
            if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                play()
            }
            true
        }.onFailure {
            Log.w(TAG, "AudioTrack play failed", it)
            notifyAudioError("AudioTrack play failed: ${it.message ?: it.javaClass.simpleName}")
            removeTrack(this)
            releaseSafely()
        }.getOrDefault(false)
    }

    private fun handlePlaybackDiagnostic(diagnostic: VoicePlaybackDiagnostic) {
        when (diagnostic) {
            is VoicePlaybackDiagnostic.ChunkQueued -> {
                Log.d(TAG, "Voice playback queued: bytes=${diagnostic.bytes} generation=${diagnostic.generation}")
            }
            is VoicePlaybackDiagnostic.ChunkWritten -> {
                Log.d(TAG, "Voice playback wrote: bytes=${diagnostic.bytes} generation=${diagnostic.generation}")
            }
            is VoicePlaybackDiagnostic.StaleChunkRejected -> {
                Log.d(
                    TAG,
                    "Voice playback stale chunk rejected: generation=${diagnostic.generation} " +
                        "active=${diagnostic.activeGeneration} session=${diagnostic.rejectedSessionId} " +
                        "activeSession=${diagnostic.activeSessionId}",
                )
            }
            is VoicePlaybackDiagnostic.MalformedChunk -> {
                Log.w(TAG, "Dropping malformed playback chunk: ${diagnostic.message}")
                notifyAudioError("Malformed playback chunk: ${diagnostic.message}")
            }
            is VoicePlaybackDiagnostic.SinkStartFailed -> {
                Log.w(TAG, "Voice playback start failed: ${diagnostic.message}")
                notifyAudioError("AudioTrack start failed: ${diagnostic.message}")
            }
            is VoicePlaybackDiagnostic.SinkWriteFailed -> {
                Log.w(TAG, "Voice playback write failed: ${diagnostic.message}")
                notifyAudioError("AudioTrack write failed: ${diagnostic.message}")
            }
            is VoicePlaybackDiagnostic.PlaybackSuppressed -> {
                Log.d(TAG, "Voice playback suppressed: generation=${diagnostic.generation}")
            }
            VoicePlaybackDiagnostic.Released -> {
                Log.d(TAG, "Voice playback released")
            }
        }
    }

    private inner class AndroidAudioTrackSink(
        private val track: AudioTrack,
    ) : VoicePcm16Sink {
        private val interrupted = AtomicBoolean(false)

        override fun start(): VoicePcm16Sink.StartResult {
            interrupted.set(false)
            return if (track.playSafely()) {
                VoicePcm16Sink.StartResult.Started
            } else {
                VoicePcm16Sink.StartResult.Failed("AudioTrack play failed")
            }
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            if (interrupted.get()) {
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            if (!track.playSafely()) {
                return VoicePcm16Sink.WriteResult.Failed("AudioTrack play failed")
            }

            var offset = 0
            var zeroWrites = 0
            while (offset < pcm16.size && !interrupted.get() && currentPlaybackTrack(track) != null) {
                val remaining = pcm16.size - offset
                val writeResult = try {
                    track.write(pcm16, offset, remaining, AudioTrack.WRITE_BLOCKING)
                } catch (e: RuntimeException) {
                    if (interrupted.get()) {
                        return VoicePcm16Sink.WriteResult.Interrupted
                    }
                    Log.w(TAG, "AudioTrack write failed", e)
                    removeTrack(track)
                    track.releaseSafely()
                    return VoicePcm16Sink.WriteResult.Failed(e.message ?: e.javaClass.simpleName)
                }

                when {
                    interrupted.get() -> {
                        return VoicePcm16Sink.WriteResult.Interrupted
                    }
                    writeResult < 0 -> {
                        Log.w(TAG, "AudioTrack write error: $writeResult")
                        removeTrack(track)
                        track.releaseSafely()
                        return VoicePcm16Sink.WriteResult.Failed("AudioTrack write error: $writeResult")
                    }
                    writeResult == 0 -> {
                        zeroWrites += 1
                        if (zeroWrites >= MAX_BLOCKING_ZERO_WRITES) {
                            removeTrack(track)
                            track.releaseSafely()
                            return VoicePcm16Sink.WriteResult.Failed(
                                "AudioTrack write made no progress after $zeroWrites attempts",
                            )
                        }
                        Thread.yield()
                    }
                    else -> {
                        offset += writeResult.coerceAtMost(remaining)
                        zeroWrites = 0
                    }
                }
            }

            return when {
                offset == pcm16.size -> VoicePcm16Sink.WriteResult.Written(offset)
                interrupted.get() -> VoicePcm16Sink.WriteResult.Interrupted
                else -> VoicePcm16Sink.WriteResult.Failed(
                    "AudioTrack write interrupted after $offset of ${pcm16.size} bytes",
                )
            }
        }

        override fun pauseAndFlush() {
            interrupted.set(true)
            track.pauseSafely()
            track.flushSafely()
        }

        override fun stopAndRelease() {
            interrupted.set(true)
            track.stopSafely()
            track.releaseSafely()
            removeTrack(track)
        }
    }

    private fun AudioRecord.stopSafely() {
        runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
        }
    }

    private fun AudioRecord.releaseSafely() {
        runCatching { release() }
    }

    private fun AudioTrack.pauseSafely() {
        runCatching { pause() }
    }

    private fun AudioTrack.flushSafely() {
        runCatching { flush() }
    }

    private fun AudioTrack.stopSafely() {
        runCatching { stop() }
    }

    private fun AudioTrack.releaseSafely() {
        runCatching { release() }
    }

    private companion object {
        const val TAG = "AndroidVoiceAudioEngine"
        const val CAPTURE_SAMPLE_RATE = 16_000
        const val PLAYBACK_SAMPLE_RATE = 24_000
        const val MIN_CAPTURE_BUFFER_BYTES = 3_200
        const val MIN_PLAYBACK_BUFFER_BYTES = 4_800
        const val MAX_BLOCKING_ZERO_WRITES = 16

        fun captureBufferSize(): Int {
            val bufferSize = AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                throw IllegalStateException("AudioRecord min buffer size failed: $bufferSize")
            }
            return bufferSize.coerceAtLeast(MIN_CAPTURE_BUFFER_BYTES)
        }

        fun playbackBufferSizeOrNull(): Int? {
            val bufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                Log.w(TAG, "AudioTrack min buffer size failed: $bufferSize")
                return null
            }
            return bufferSize.coerceAtLeast(MIN_PLAYBACK_BUFFER_BYTES)
        }
    }
}
