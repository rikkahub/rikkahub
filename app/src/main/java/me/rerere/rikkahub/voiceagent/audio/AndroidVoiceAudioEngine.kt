package me.rerere.rikkahub.voiceagent.audio

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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

internal fun VoicePlaybackDiagnostic.audioErrorMessageOrNull(): String? = when (this) {
    is VoicePlaybackDiagnostic.MalformedChunk -> when (source) {
        VoicePlaybackSource.Assistant -> "Malformed playback chunk: $message"
        VoicePlaybackSource.LocalCue -> null
    }
    is VoicePlaybackDiagnostic.SinkStartFailed -> when (source) {
        VoicePlaybackSource.Assistant -> "AudioTrack start failed: $message"
        VoicePlaybackSource.LocalCue -> null
    }
    is VoicePlaybackDiagnostic.SinkWriteFailed -> when (source) {
        VoicePlaybackSource.Assistant -> "AudioTrack write failed: $message"
        VoicePlaybackSource.LocalCue -> null
    }
    is VoicePlaybackDiagnostic.ChunkQueued,
    is VoicePlaybackDiagnostic.ChunkWritten,
    is VoicePlaybackDiagnostic.StaleChunkRejected,
    is VoicePlaybackDiagnostic.PlaybackSuppressed,
    VoicePlaybackDiagnostic.Released,
    -> null
}

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
    private var hasSelectedCommunicationDevice = false
    private var hasStartedBluetoothSco = false
    private var bluetoothProfileProxyRequested = false
    private var wantsBluetoothHeadsetVoiceRecognition = false
    private var bluetoothVoiceRecognitionDevice: BluetoothDevice? = null
    @Volatile
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var previousAudioMode: Int? = null
    private var debugCaptureRegistration: VoiceAudioDebugInjector.Registration? = null
    private var hasAudioFocus = false
    private var captureGeneration = 0L
    private var errorHandler: ((String) -> Unit)? = null
    private var released = false
    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as? BluetoothHeadset
                Log.d(TAG, "Voice capture Bluetooth headset profile connected")
                val headset = bluetoothHeadset
                if (wantsBluetoothHeadsetVoiceRecognition && headset != null) {
                    scope.launch {
                        requestBluetoothHeadsetVoiceRecognition(headset)
                    }
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                bluetoothVoiceRecognitionDevice = null
                bluetoothProfileProxyRequested = false
                wantsBluetoothHeadsetVoiceRecognition = false
                Log.d(TAG, "Voice capture Bluetooth headset profile disconnected")
            }
        }
    }

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
        requestAudioFocusBestEffort()
        prepareVoiceCommunicationRoutingBestEffort()

        val generation = synchronized(lock) {
            check(!released) { "Voice audio engine is released" }
            captureGeneration += 1
            captureGeneration
        }
        val bufferSize = captureBufferSize()
        val preferredCaptureDevice = selectPreferredBluetoothCaptureDeviceOrNull()
        val recorder = runCatching {
            createCaptureRecord(bufferSize = bufferSize)
        }.getOrElse {
            throw IllegalStateException("AudioRecord creation failed", it)
        }
        preferredCaptureDevice?.let { device ->
            val preferredAccepted = runCatching { recorder.setPreferredDevice(device) }
                .onFailure { Log.w(TAG, "Voice capture preferred Bluetooth device failed", it) }
                .getOrDefault(false)
            val communicationAccepted = setCommunicationDeviceBestEffort(device)
            Log.d(
                TAG,
                "Voice capture selected route=${device.safeRouteLabel()} " +
                    "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
            )
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.releaseSafely()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val buffer = ByteArray(bufferSize)
            var captureLevelChunks = 0
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
                            val pcm16 = buffer.copyOf(read)
                            captureLevelChunks += 1
                            logCaptureLevelIfNeeded(chunk = captureLevelChunks, pcm16 = pcm16)
                            deliverCaptureBuffer(generation, recorder, pcm16, onPcm16)
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
        clearVoiceCommunicationRoutingBestEffort()
        waitForCaptureCallbacks()
    }

    override fun playPcm16(base64Pcm16: String) {
        playPcm16(base64Pcm16 = base64Pcm16, sessionId = null)
    }

    override fun playPcm16(base64Pcm16: String, sessionId: Long?) {
        playbackWriter.playBase64(base64Pcm16 = base64Pcm16, sessionId = sessionId)
    }

    override fun playLocalCuePcm16(base64Pcm16: String, sessionId: Long?): Boolean {
        return playbackWriter.playBase64(
            base64Pcm16 = base64Pcm16,
            sessionId = sessionId,
            source = VoicePlaybackSource.LocalCue,
        )
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
        clearVoiceCommunicationRoutingBestEffort()
        closeBluetoothHeadsetProxy()
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

    private fun selectPreferredBluetoothCaptureDeviceOrNull(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            Log.d(TAG, "Voice capture Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.onFailure {
            Log.w(TAG, "Voice capture route enumeration failed", it)
        }.getOrDefault(emptyList())
        val routeDevices = devices.map { it.toVoiceAudioRouteDevice() }
        val selected = selectPreferredCaptureRoute(routeDevices)
        Log.d(
            TAG,
            "Voice capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selected?.debugLabel() ?: "default"}",
        )
        return selected?.let { route -> devices.firstOrNull { it.id == route.id } }
    }

    @SuppressLint("MissingPermission")
    private fun setCommunicationDeviceBestEffort(device: AudioDeviceInfo): Boolean {
        val manager = audioManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return runCatching {
            val accepted = manager.setCommunicationDevice(device)
            if (accepted) {
                hasSelectedCommunicationDevice = true
            }
            accepted
        }.onFailure {
            Log.w(TAG, "Voice capture communication route failed", it)
        }.getOrDefault(false)
    }

    private fun clearCommunicationDeviceSelection() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasSelectedCommunicationDevice) {
            return
        }
        runCatching {
            manager.clearCommunicationDevice()
        }.onFailure {
            Log.w(TAG, "Voice capture communication route clear failed", it)
        }
        hasSelectedCommunicationDevice = false
    }

    @Suppress("DEPRECATION")
    private fun prepareVoiceCommunicationRoutingBestEffort() {
        val manager = audioManager ?: return
        runCatching {
            if (previousAudioMode == null) {
                previousAudioMode = manager.mode
            }
            if (manager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        }.onFailure {
            Log.w(TAG, "Voice capture communication mode setup failed", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            Log.d(TAG, "Voice capture Bluetooth SCO skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        startBluetoothHeadsetVoiceRecognitionBestEffort()
        runCatching {
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
            hasStartedBluetoothSco = true
            Log.d(TAG, "Voice capture requested Bluetooth SCO")
        }.onFailure {
            Log.w(TAG, "Voice capture Bluetooth SCO request failed", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun clearVoiceCommunicationRoutingBestEffort() {
        val manager = audioManager ?: return
        clearCommunicationDeviceSelection()
        stopBluetoothHeadsetVoiceRecognitionBestEffort()
        if (hasStartedBluetoothSco) {
            runCatching {
                manager.isBluetoothScoOn = false
                manager.stopBluetoothSco()
            }.onFailure {
                Log.w(TAG, "Voice capture Bluetooth SCO stop failed", it)
            }
            hasStartedBluetoothSco = false
        }
        previousAudioMode?.let { mode ->
            runCatching {
                manager.mode = mode
            }.onFailure {
                Log.w(TAG, "Voice capture communication mode restore failed", it)
            }
            previousAudioMode = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothHeadsetVoiceRecognitionBestEffort() {
        wantsBluetoothHeadsetVoiceRecognition = true
        val headset = bluetoothHeadsetProxyOrNull()
        if (headset == null) {
            Log.d(TAG, "Voice capture Bluetooth headset voice recognition skipped: profile unavailable")
            return
        }
        requestBluetoothHeadsetVoiceRecognition(headset)
    }

    @SuppressLint("MissingPermission")
    private fun requestBluetoothHeadsetVoiceRecognition(headset: BluetoothHeadset) {
        if (!wantsBluetoothHeadsetVoiceRecognition || bluetoothVoiceRecognitionDevice != null) {
            return
        }
        val device = runCatching {
            headset.connectedDevices.firstOrNull()
        }.onFailure {
            Log.w(TAG, "Voice capture Bluetooth headset device lookup failed", it)
        }.getOrNull()
        if (device == null) {
            Log.d(TAG, "Voice capture Bluetooth headset voice recognition skipped: no connected headset")
            return
        }
        val accepted = runCatching {
            headset.startVoiceRecognition(device)
        }.onFailure {
            Log.w(TAG, "Voice capture Bluetooth headset voice recognition request failed", it)
        }.getOrDefault(false)
        if (accepted) {
            bluetoothVoiceRecognitionDevice = device
        }
        Log.d(
            TAG,
            "Voice capture Bluetooth headset voice recognition requested device=${device.safeBluetoothLabel()} " +
                "accepted=$accepted",
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothHeadsetVoiceRecognitionBestEffort() {
        wantsBluetoothHeadsetVoiceRecognition = false
        val headset = bluetoothHeadset ?: return
        val device = bluetoothVoiceRecognitionDevice ?: return
        runCatching {
            headset.stopVoiceRecognition(device)
        }.onFailure {
            Log.w(TAG, "Voice capture Bluetooth headset voice recognition stop failed", it)
        }
        bluetoothVoiceRecognitionDevice = null
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothHeadsetProxyOrNull(): BluetoothHeadset? {
        bluetoothHeadset?.let { return it }
        if (!bluetoothProfileProxyRequested) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            bluetoothProfileProxyRequested = runCatching {
                adapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET) == true
            }.onFailure {
                Log.w(TAG, "Voice capture Bluetooth headset profile request failed", it)
            }.getOrDefault(false)
        }
        repeat(BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS) {
            bluetoothHeadset?.let { return it }
            Thread.sleep(BLUETOOTH_HEADSET_PROFILE_WAIT_MS)
        }
        return bluetoothHeadset
    }

    private fun closeBluetoothHeadsetProxy() {
        val headset = bluetoothHeadset ?: return
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.closeProfileProxy(BluetoothProfile.HEADSET, headset)
        }.onFailure {
            Log.w(TAG, "Voice capture Bluetooth headset profile close failed", it)
        }
        bluetoothHeadset = null
        bluetoothProfileProxyRequested = false
        bluetoothVoiceRecognitionDevice = null
    }

    private fun createAudioTrackSinkOrNull(source: VoicePlaybackSource): VoicePcm16Sink? {
        val track = getOrCreatePlaybackTrack(source) ?: return null
        return AndroidAudioTrackSink(track = track)
    }

    private fun getOrCreatePlaybackTrack(source: VoicePlaybackSource): AudioTrack? {
        val existingTrack = synchronized(lock) { audioTrack }
        if (existingTrack != null) {
            return currentPlaybackTrack(existingTrack)
        }

        val newTrack = createAudioTrackOrNull(source) ?: return null
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

    private fun createAudioTrackOrNull(source: VoicePlaybackSource): AudioTrack? {
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
            if (source == VoicePlaybackSource.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack creation failed")
            } else {
                notifyAudioError("AudioTrack creation failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
            if (source == VoicePlaybackSource.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack initialization failed")
            } else {
                notifyAudioError("AudioTrack initialization failed: state=${track.state}")
            }
            track.releaseSafely()
            return null
        }

        return track
    }

    private fun requestAudioFocusBestEffort() {
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
                    if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                        notifyAudioError("Audio focus lost: $focusChange")
                    } else if (focusChange < 0) {
                        Log.w(TAG, "Recoverable audio focus change: $focusChange")
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
            if (VoiceAudioFocusPolicy.isRequestFailureFatal(result)) {
                throw IllegalStateException("Voice Agent audio focus request failed: $result")
            }
            Log.w(TAG, "Voice Agent audio focus request was not granted: $result")
            return
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

    private fun hasBluetoothConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun AudioDeviceInfo.toVoiceAudioRouteDevice(): VoiceAudioRouteDevice =
        VoiceAudioRouteDevice(
            id = id,
            type = when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> VoiceAudioRouteDeviceType.BluetoothSco
                AudioDeviceInfo.TYPE_BLE_HEADSET -> VoiceAudioRouteDeviceType.BluetoothBleHeadset
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> VoiceAudioRouteDeviceType.BuiltInMic
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> VoiceAudioRouteDeviceType.WiredHeadset
                else -> VoiceAudioRouteDeviceType.Other
            },
            name = productName?.toString().orEmpty(),
        )

    private fun VoiceAudioRouteDevice.debugLabel(): String =
        "$id:${type.name}:${name.ifBlank { "unnamed" }}"

    private fun AudioDeviceInfo.safeRouteLabel(): String =
        "${id}:${toVoiceAudioRouteDevice().type.name}:${productName?.toString().orEmpty().ifBlank { "unnamed" }}"

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeBluetoothLabel(): String =
        "${name ?: "unnamed"}:${address ?: "unknown"}"

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

    private fun AudioTrack.playSafely(source: VoicePlaybackSource): Boolean {
        return runCatching {
            if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                play()
            }
            true
        }.onFailure {
            Log.w(TAG, "AudioTrack play failed", it)
            if (source == VoicePlaybackSource.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack play failed")
            } else {
                notifyAudioError("AudioTrack play failed: ${it.message ?: it.javaClass.simpleName}")
            }
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
                if (diagnostic.source == VoicePlaybackSource.LocalCue) {
                    Log.w(TAG, "Local cue playback failed: ${diagnostic.message}")
                } else {
                    Log.w(TAG, "Dropping malformed playback chunk: ${diagnostic.message}")
                }
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.SinkStartFailed -> {
                if (diagnostic.source == VoicePlaybackSource.LocalCue) {
                    Log.w(TAG, "Local cue playback failed: ${diagnostic.message}")
                } else {
                    Log.w(TAG, "Voice playback start failed: ${diagnostic.message}")
                }
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
            }
            is VoicePlaybackDiagnostic.SinkWriteFailed -> {
                if (diagnostic.source == VoicePlaybackSource.LocalCue) {
                    Log.w(TAG, "Local cue playback failed: ${diagnostic.message}")
                } else {
                    Log.w(TAG, "Voice playback write failed: ${diagnostic.message}")
                }
                diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
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

        override fun start(source: VoicePlaybackSource): VoicePcm16Sink.StartResult {
            interrupted.set(false)
            return if (track.playSafely(source)) {
                VoicePcm16Sink.StartResult.Started
            } else {
                VoicePcm16Sink.StartResult.Failed("AudioTrack play failed")
            }
        }

        override fun writeFully(pcm16: ByteArray, source: VoicePlaybackSource): VoicePcm16Sink.WriteResult {
            if (interrupted.get()) {
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            if (!track.playSafely(source)) {
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
        const val CAPTURE_LEVEL_LOG_INTERVAL_CHUNKS = 10
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS = 10
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_MS = 100L

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
