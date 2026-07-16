package me.rerere.rikkahub.voiceagent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.RetirementBarrier

private fun VoicePlaybackDiagnostic.audioErrorMessageOrNull(): String? = when (this) {
    is VoicePlaybackDiagnostic.MalformedChunk -> "Malformed playback chunk: $message"
    is VoicePlaybackDiagnostic.SinkStartFailed -> "AudioTrack start failed: $message"
    is VoicePlaybackDiagnostic.SinkWriteFailed -> "AudioTrack write failed: $message"
    is VoicePlaybackDiagnostic.SinkDrainFailed -> "AudioTrack drain failed: $message"
    is VoicePlaybackDiagnostic.SinkRetirementFailed -> "AudioTrack retirement failed: $message"
    is VoicePlaybackDiagnostic.ChunkQueued,
    is VoicePlaybackDiagnostic.ChunkWritten,
    is VoicePlaybackDiagnostic.StaleChunkRejected,
    is VoicePlaybackDiagnostic.PlaybackEventHandlerFailed,
    is VoicePlaybackDiagnostic.PlaybackSuppressed,
    VoicePlaybackDiagnostic.Released,
    -> null
}

internal class VoiceAudioCaptureLifecycle {
    private val lock = Any()

    fun <T> transition(block: () -> T): T = synchronized(lock) { block() }

    fun <T> callback(block: () -> T): T = synchronized(lock) { block() }
}

internal class VoiceAudioCaptureRouteLease(
    private val afterCapture: () -> Unit,
) {
    private val retirement = RetirementBarrier()

    fun retire() {
        retirement.retire {
            afterCapture()
        }
    }
}

internal fun runVoiceAudioCaptureLoop(
    bufferSize: Int,
    shouldContinue: () -> Boolean,
    read: (ByteArray) -> Int,
    onPcm16: (ByteArray) -> Unit,
    onReadException: (RuntimeException) -> Unit,
    onNegativeRead: (Int) -> Unit,
    onPcmCallbackException: (Exception) -> Unit,
    onTerminated: () -> Unit,
) {
    val buffer = ByteArray(bufferSize)
    try {
        while (shouldContinue()) {
            val readCount = try {
                read(buffer)
            } catch (error: RuntimeException) {
                onReadException(error)
                break
            }
            when (readCount) {
                in 1..buffer.size -> try {
                    onPcm16(buffer.copyOf(readCount))
                } catch (error: Exception) {
                    onPcmCallbackException(error)
                    break
                }
                0 -> Unit
                else -> {
                    onNegativeRead(readCount)
                    break
                }
            }
        }
    } finally {
        onTerminated()
    }
}

internal fun ensureVoiceAudioCaptureRecorderInitialized(
    initialized: Boolean,
    releaseRecorder: () -> Unit,
    clearRouteLease: () -> Unit,
    routeLease: VoiceAudioCaptureRouteLease,
) {
    if (initialized) return
    val failure = IllegalStateException("AudioRecord initialization failed")
    listOf(
        releaseRecorder,
        clearRouteLease,
        routeLease::retire,
    ).forEach { cleanup ->
        runCatching(cleanup).exceptionOrNull()?.let(failure::addSuppressed)
    }
    throw failure
}

class AndroidVoiceAudioEngine(
    context: Context,
    routeOwner: VoiceAudioRouteOwner,
) : VoiceAudioEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val captureRecordLock = Any()
    private val captureLifecycle = VoiceAudioCaptureLifecycle()
    private val routeController = selectVoiceAudioRouteController(routeOwner) {
        AndroidDirectAudioRouteController(this.context, ::notifyAudioError)
    }
    private val playbackTracks = AndroidVoicePlaybackTracks(
        audioAttributes = ::voiceAudioAttributes,
        onAssistantPlaybackError = ::notifyAudioError,
    )
    private val playbackEventOwner = VoicePlaybackEventOwner()
    private val playbackWriter = VoicePlaybackWriter(
        scope = scope,
        createSink = playbackTracks::createAssistantSinkOrNull,
        onDiagnostic = ::handlePlaybackDiagnostic,
        onPlaybackEvent = ::notifyPlaybackEvent,
    )
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var captureRouteLease: VoiceAudioCaptureRouteLease? = null
    private var debugCaptureRegistration: VoiceAudioDebugInjector.Registration? = null
    private var captureGeneration = 0L
    private var errorHandler: ((String) -> Unit)? = null
    private var released = false

    init {
        Log.d(TAG, "Voice audio route owner=${routeOwner.diagnosticLabel}")
    }

    override fun setErrorHandler(onError: ((String) -> Unit)?) {
        synchronized(lock) {
            errorHandler = onError
        }
    }

    override fun setPlaybackEventHandler(onEvent: ((VoicePlaybackEvent) -> Unit)?) {
        playbackEventOwner.setHandler(onEvent)
    }

    override fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) {
        captureLifecycle.transition {
            startCaptureLocked(onPcm16, onDebugInjectionComplete)
        }
    }

    private fun startCaptureLocked(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Microphone permission is required")
        }

        stopCaptureLocked()
        routeController.beforeCapture()
        val routeLease = VoiceAudioCaptureRouteLease(routeController::afterCapture)
        val generation = try {
            synchronized(lock) {
                check(!released) { "Voice audio engine is released" }
                captureGeneration += 1
                captureRouteLease = routeLease
                captureGeneration
            }
        } catch (error: Throwable) {
            routeLease.retire()
            throw error
        }
        val bufferSize = try {
            captureBufferSize()
        } catch (error: Throwable) {
            clearCaptureRouteLease(generation, routeLease)
            routeLease.retire()
            throw error
        }
        val recorder = runCatching {
            createCaptureRecord(bufferSize = bufferSize)
        }.getOrElse {
            clearCaptureRouteLease(generation, routeLease)
            routeLease.retire()
            throw IllegalStateException("AudioRecord creation failed", it)
        }
        routeController.configureRecorder(recorder)

        ensureVoiceAudioCaptureRecorderInitialized(
            initialized = recorder.state == AudioRecord.STATE_INITIALIZED,
            releaseRecorder = { recorder.releaseSafely() },
            clearRouteLease = { clearCaptureRouteLease(generation, routeLease) },
            routeLease = routeLease,
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var captureLevelChunks = 0
            runVoiceAudioCaptureLoop(
                bufferSize = bufferSize,
                shouldContinue = { isActive && isCurrentCapture(generation, recorder) },
                read = { buffer -> recorder.read(buffer, 0, buffer.size) },
                onPcm16 = { pcm16 ->
                    captureLevelChunks += 1
                    logCaptureLevelIfNeeded(chunk = captureLevelChunks, pcm16 = pcm16)
                    deliverCaptureBuffer(generation, recorder, pcm16, onPcm16)
                },
                onReadException = { error ->
                    if (isCurrentCapture(generation, recorder)) {
                        Log.w(TAG, "AudioRecord read failed", error)
                        notifyAudioError(
                            "AudioRecord read failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                },
                onNegativeRead = { read ->
                    if (isCurrentCapture(generation, recorder)) {
                        val error = IllegalStateException("AudioRecord read error: $read")
                        Log.w(TAG, "Stopping capture after AudioRecord read failure", error)
                        notifyAudioError(error.message ?: error.javaClass.simpleName)
                    }
                },
                onPcmCallbackException = { error ->
                    if (isCurrentCapture(generation, recorder)) {
                        Log.w(TAG, "Stopping capture after PCM callback failure", error)
                    }
                },
                onTerminated = {
                    stopAndReleaseRecorder(recorder)
                    clearRecorder(generation, recorder)
                    routeLease.retire()
                },
            )
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
            clearCaptureRouteLease(generation, routeLease)
            routeLease.retire()
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
                clearCaptureRouteLease(generation, routeLease)
                routeLease.retire()
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
                clearCaptureRouteLease(generation, routeLease)
                routeLease.retire()
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
                clearCaptureRouteLease(generation, routeLease)
                routeLease.retire()
            }
        }
    }

    override fun stopCapture() {
        captureLifecycle.transition(::stopCaptureLocked)
    }

    private fun stopCaptureLocked() {
        val job: Job?
        val recorder: AudioRecord?
        val routeLease: VoiceAudioCaptureRouteLease?
        synchronized(lock) {
            captureGeneration += 1
            job = captureJob
            captureJob = null
            recorder = audioRecord
            audioRecord = null
            routeLease = captureRouteLease
            captureRouteLease = null
            unregisterDebugCaptureLocked()
        }
        job?.cancel()
        recorder?.let(::stopAndReleaseRecorder)
        routeLease?.retire()
        waitForCaptureCallbacks()
    }

    override fun playPcm16(base64Pcm16: String, sessionId: Long?): Boolean {
        return playbackWriter.playBase64(base64Pcm16 = base64Pcm16, sessionId = sessionId)
    }

    override fun activatePlaybackSession(sessionId: Long) {
        playbackWriter.activateSession(sessionId)
    }

    override fun markPlaybackTurnComplete(sessionId: Long?): Boolean =
        playbackWriter.markTurnComplete(sessionId)

    override fun invalidatePlaybackSession() {
        playbackWriter.invalidateSession()
    }

    override fun suppressPlayback() {
        playbackWriter.suppress()
    }

    override fun release() {
        captureLifecycle.transition(::releaseLocked)
    }

    private fun releaseLocked() {
        val job: Job?
        val recorder: AudioRecord?
        val routeLease: VoiceAudioCaptureRouteLease?
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
            routeLease = captureRouteLease
            captureRouteLease = null
            unregisterDebugCaptureLocked()
        }
        playbackTracks.markReleased()
        job?.cancel()
        recorder?.let(::stopAndReleaseRecorder)
        routeLease?.retire()
        playbackEventOwner.releasePlayback(playbackWriter::release)
        playbackTracks.releaseAll()
        routeController.close()
        waitForCaptureCallbacks()
        scope.cancel()
    }

    private fun clearRecorder(generation: Long, recorder: AudioRecord) {
        synchronized(lock) {
            if (captureGeneration == generation && audioRecord === recorder) {
                captureJob = null
                audioRecord = null
                captureRouteLease = null
                unregisterDebugCaptureLocked()
            }
        }
    }

    private fun clearCaptureRouteLease(generation: Long, routeLease: VoiceAudioCaptureRouteLease) {
        synchronized(lock) {
            if (captureGeneration == generation && captureRouteLease === routeLease) {
                captureRouteLease = null
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
                captureLifecycle.callback {
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
        captureLifecycle.callback {
            if (isCurrentCapture(generation, recorder)) {
                onPcm16(buffer)
            }
        }
    }

    private fun deliverInjectedCaptureBuffer(
        generation: Long,
        recorder: AudioRecord,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        captureLifecycle.callback {
            if (!isCurrentCapture(generation, recorder)) return@callback
            try {
                onPcm16(buffer)
            } catch (e: Exception) {
                Log.w(TAG, "Stopping capture after debug PCM injection callback failure", e)
                invalidateCapture(generation, recorder)
            }
        }
    }

    private fun waitForCaptureCallbacks() {
        captureLifecycle.callback {
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createCaptureRecord(bufferSize: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setSampleRate(CAPTURE_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize * 2)
            .build()
    }

    private fun notifyAudioError(message: String) {
        val handler = synchronized(lock) {
            if (released) null else errorHandler
        }
        handler?.invoke(message)
    }

    private fun notifyPlaybackEvent(event: VoicePlaybackEvent) {
        when (event) {
            is VoicePlaybackEvent.Active ->
                Log.d(TAG, "Voice playback active: playbackEpoch=${event.playbackEpoch.value}")
            is VoicePlaybackEvent.DrainStarted ->
                Log.d(TAG, "Voice playback drain started: playbackEpoch=${event.playbackEpoch.value}")
            is VoicePlaybackEvent.Drained ->
                Log.d(TAG, "Voice playback drained: playbackEpoch=${event.playbackEpoch.value}")
        }
        playbackEventOwner.notify(event)
    }

    private fun logCaptureLevelIfNeeded(chunk: Int, pcm16: ByteArray) {
        if (chunk != 1 && chunk % CAPTURE_LEVEL_LOG_INTERVAL_CHUNKS != 0) {
            return
        }
        val level = voicePcm16Level(pcm16)
        Log.d(
            TAG,
            "Voice capture level chunk=$chunk bytes=${pcm16.size} samples=${level.samples} " +
                "rms=${level.rms} peak=${level.peak} zeroCrossings=${level.zeroCrossings}",
        )
    }

    private fun handlePlaybackDiagnostic(diagnostic: VoicePlaybackDiagnostic) {
        when (diagnostic) {
            is VoicePlaybackDiagnostic.ChunkQueued -> {
                Log.d(
                    TAG,
                    "Voice playback queued: bytes=${diagnostic.bytes} " +
                        "writerGeneration=${diagnostic.writerGeneration.value}",
                )
            }
            is VoicePlaybackDiagnostic.ChunkWritten -> {
                Log.d(
                    TAG,
                    "Voice playback wrote: bytes=${diagnostic.bytes} " +
                        "writerGeneration=${diagnostic.writerGeneration.value}",
                )
            }
            is VoicePlaybackDiagnostic.StaleChunkRejected -> {
                Log.d(
                    TAG,
                    "Voice playback stale chunk rejected: " +
                        "writerGeneration=${diagnostic.writerGeneration.value} " +
                        "activeWriterGeneration=${diagnostic.activeWriterGeneration.value} " +
                        "session=${diagnostic.rejectedSessionId} " +
                        "activeSession=${diagnostic.activeSessionId}",
                )
            }
            is VoicePlaybackDiagnostic.MalformedChunk -> {
                Log.w(TAG, "Dropping malformed playback chunk: ${diagnostic.message}")
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.SinkStartFailed -> {
                Log.w(TAG, "Voice playback start failed: ${diagnostic.message}")
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.SinkWriteFailed -> {
                Log.w(TAG, "Voice playback write failed: ${diagnostic.message}")
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.SinkDrainFailed -> {
                Log.w(TAG, "AudioTrack drain failed: ${diagnostic.message}")
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.SinkRetirementFailed -> {
                Log.w(TAG, "AudioTrack retirement failed: ${diagnostic.message}")
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.PlaybackEventHandlerFailed -> {
                Log.w(
                    TAG,
                    "Voice playback event handler failed: event=${diagnostic.event} " +
                        "message=${diagnostic.message}",
                )
            }
            is VoicePlaybackDiagnostic.PlaybackSuppressed -> {
                Log.d(
                    TAG,
                    "Voice playback suppressed: writerGeneration=${diagnostic.writerGeneration.value}",
                )
            }
            VoicePlaybackDiagnostic.Released -> {
                Log.d(TAG, "Voice playback released")
            }
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

    private companion object {
        const val TAG = "AndroidVoiceAudioEngine"
        const val CAPTURE_SAMPLE_RATE = 16_000
        const val MIN_CAPTURE_BUFFER_BYTES = 3_200
        const val CAPTURE_LEVEL_LOG_INTERVAL_CHUNKS = 10

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
    }
}
