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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.voiceagent.runVoiceAgentCleanupStages

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

internal data class VoiceAudioCaptureSetup<Recorder : Any>(
    val token: VoiceAudioCaptureToken,
    val bufferSize: Int,
    val recorder: Recorder,
)

internal data class VoiceAudioCaptureAdmission<Recorder : Any>(
    val token: VoiceAudioCaptureToken,
    val recorder: Recorder,
)

internal suspend fun <Recorder : Any, CaptureTask : Any> setupVoiceAudioCapture(
    ownership: VoiceAudioCaptureOwnership<Recorder, CaptureTask>,
    acquireRoute: () -> VoiceAudioCaptureRouteLease,
    lookupBufferSize: () -> Int,
    createRecorder: (Int) -> Recorder,
    configureRecorder: (VoiceAudioCaptureRouteLease, Recorder) -> Unit,
    isRecorderInitialized: (Recorder) -> Boolean,
    releaseRecorder: (Recorder) -> Unit,
): VoiceAudioCaptureSetup<Recorder>? {
    val token = ownership.reserve()
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancellation: CancellationException) {
        throwVoiceAudioCaptureSetupFailure(cancellation, { ownership.abort(token) })
    }
    val routeLease = try {
        acquireRoute()
    } catch (failure: Throwable) {
        throwVoiceAudioCaptureSetupFailure(failure, { ownership.abort(token) })
    }
    if (!ownership.publishRoute(token, routeLease)) {
        routeLease.retire()
        return null
    }
    try {
        routeLease.prepare()
    } catch (failure: Throwable) {
        throwVoiceAudioCaptureSetupFailure(failure, { ownership.abort(token) })
    }
    val bufferSize = try {
        lookupBufferSize()
    } catch (failure: Throwable) {
        throwVoiceAudioCaptureSetupFailure(failure, { ownership.abort(token) })
    }
    val recorder = try {
        createRecorder(bufferSize)
    } catch (cause: Throwable) {
        throwVoiceAudioCaptureSetupFailure(
            IllegalStateException("AudioRecord creation failed", cause),
            { ownership.abort(token) },
        )
    }
    try {
        configureRecorder(routeLease, recorder)
    } catch (failure: Throwable) {
        throwVoiceAudioCaptureSetupFailure(
            failure,
            { releaseRecorder(recorder) },
            { ownership.abort(token) },
        )
    }
    if (!isRecorderInitialized(recorder)) {
        throwVoiceAudioCaptureSetupFailure(
            IllegalStateException("AudioRecord initialization failed"),
            { releaseRecorder(recorder) },
            { ownership.abort(token) },
        )
    }
    return VoiceAudioCaptureSetup(token, bufferSize, recorder)
}

private fun throwVoiceAudioCaptureSetupFailure(
    failure: Throwable,
    vararg cleanupStages: () -> Unit,
): Nothing {
    runVoiceAgentCleanupStages(
        { throw failure },
        *cleanupStages,
    )
    error("Capture setup cleanup returned without its primary failure")
}

internal suspend fun <Recorder : Any, CaptureTask : Any> publishVoiceAudioCapture(
    ownership: VoiceAudioCaptureOwnership<Recorder, CaptureTask>,
    setup: VoiceAudioCaptureSetup<Recorder>,
    task: CaptureTask,
    cancelTask: (CaptureTask) -> Unit,
    releaseRecorder: (Recorder) -> Unit,
): VoiceAudioCaptureStartOutcome {
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancellation: CancellationException) {
        throwVoiceAudioCaptureSetupFailure(
            cancellation,
            { cancelTask(task) },
            { releaseRecorder(setup.recorder) },
            { ownership.abort(setup.token) },
        )
    }
    return ownership.publishAndStart(setup.token, setup.recorder, task)
}

internal suspend fun <Recorder : Any> runVoiceAudioCaptureStartOnDispatcher(
    dispatcher: CoroutineDispatcher,
    startCapture: suspend (onStarted: (VoiceAudioCaptureAdmission<Recorder>) -> Unit) -> Unit,
    retireCapture: (VoiceAudioCaptureAdmission<Recorder>) -> Unit,
    unregisterDebugCapture: (VoiceAudioCaptureAdmission<Recorder>) -> Unit,
) {
    val admission = AtomicReference<VoiceAudioCaptureAdmission<Recorder>?>()
    try {
        withContext(dispatcher) {
            startCapture(admission::set)
        }
    } catch (cancellation: CancellationException) {
        val callerCancellation = cancellation.canonicalVoiceAudioCaptureCancellation()
        admission.get()?.let { admittedCapture ->
            runCatching {
                runVoiceAgentCleanupStages(
                    { retireCapture(admittedCapture) },
                    { unregisterDebugCapture(admittedCapture) },
                )
            }
                .exceptionOrNull()
                ?.let(callerCancellation::addVoiceAudioCaptureCleanupFailures)
        }
        throw callerCancellation
    }
}

private fun CancellationException.addVoiceAudioCaptureCleanupFailures(failure: Throwable) {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    seen += this
    suppressed.forEach { seen += it }
    (sequenceOf(failure) + failure.suppressed.asSequence())
        .filter(seen::add)
        .forEach(::addSuppressed)
}

private fun CancellationException.canonicalVoiceAudioCaptureCancellation(): CancellationException {
    var canonical = this
    val visited = Collections.newSetFromMap(
        IdentityHashMap<CancellationException, Boolean>(),
    )
    visited += canonical
    while (true) {
        val original = canonical.cause as? CancellationException ?: return canonical
        if (original.message != canonical.message || !visited.add(original)) return canonical
        canonical = original
    }
}

class AndroidVoiceAudioEngine(
    context: Context,
    routeOwner: VoiceAudioRouteOwner,
) : VoiceAudioEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val routeController = selectVoiceAudioRouteController(routeOwner) {
        AndroidDirectAudioRouteController(this.context, ::notifyAudioError)
    }
    private val captureOwnership = VoiceAudioCaptureOwnership<AudioRecord, Job>(
        startRecorder = AudioRecord::startRecording,
        isRecorderRecording = { it.recordingState == AudioRecord.RECORDSTATE_RECORDING },
        stopRecorder = { it.stopSafely() },
        releaseRecorder = { it.releaseSafely() },
        startTask = Job::start,
        cancelTask = { it.cancel() },
    )
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
    private val debugCaptureRegistrations =
        VoiceAudioDebugCaptureRegistrationOwner<
            VoiceAudioCaptureToken,
            AudioRecord,
            VoiceAudioDebugInjector.Registration,
        >(VoiceAudioDebugInjector.Registration::close)
    private var errorHandler: ((String) -> Unit)? = null

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

    override suspend fun startCapture(
        onPcm16: (ByteArray) -> Unit,
        onDebugInjectionComplete: () -> Unit,
    ) = runVoiceAudioCaptureStartOnDispatcher<AudioRecord>(
        dispatcher = Dispatchers.IO,
        startCapture = { onStarted ->
            startCaptureInternal(onPcm16, onDebugInjectionComplete, onStarted)
        },
        retireCapture = { admission -> captureOwnership.abort(admission.token) },
        unregisterDebugCapture = { admission ->
            debugCaptureRegistrations.unregister(admission.token, admission.recorder)
        },
    )

    private suspend fun startCaptureInternal(
        onPcm16: (ByteArray) -> Unit,
        onDebugInjectionComplete: () -> Unit,
        onStarted: (VoiceAudioCaptureAdmission<AudioRecord>) -> Unit,
    ) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Microphone permission is required")
        }

        stopCaptureInternal()
        val setup = setupVoiceAudioCapture(
            ownership = captureOwnership,
            acquireRoute = routeController::acquireCapture,
            lookupBufferSize = ::captureBufferSize,
            createRecorder = { bufferSize -> createCaptureRecord(bufferSize) },
            configureRecorder = { routeLease, recorder -> routeLease.configureRecorder(recorder) },
            isRecorderInitialized = { recorder -> recorder.state == AudioRecord.STATE_INITIALIZED },
            releaseRecorder = { recorder -> recorder.releaseSafely() },
        ) ?: return
        val token = setup.token
        val bufferSize = setup.bufferSize
        val recorder = setup.recorder

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var captureLevelChunks = 0
            runVoiceAudioCaptureLoop(
                bufferSize = bufferSize,
                shouldContinue = { isActive && captureOwnership.isCurrent(token, recorder) },
                read = { buffer -> recorder.read(buffer, 0, buffer.size) },
                onPcm16 = { pcm16 ->
                    captureLevelChunks += 1
                    logCaptureLevelIfNeeded(chunk = captureLevelChunks, pcm16 = pcm16)
                    deliverCaptureBuffer(token, recorder, pcm16, onPcm16)
                },
                onReadException = { error ->
                    if (captureOwnership.isCurrent(token, recorder)) {
                        Log.w(TAG, "AudioRecord read failed", error)
                        notifyAudioError(
                            "AudioRecord read failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                },
                onNegativeRead = { read ->
                    if (captureOwnership.isCurrent(token, recorder)) {
                        val error = IllegalStateException("AudioRecord read error: $read")
                        Log.w(TAG, "Stopping capture after AudioRecord read failure", error)
                        notifyAudioError(error.message ?: error.javaClass.simpleName)
                    }
                },
                onPcmCallbackException = { error ->
                    if (captureOwnership.isCurrent(token, recorder)) {
                        Log.w(TAG, "Stopping capture after PCM callback failure", error)
                    }
                },
                onTerminated = {
                    try {
                        captureOwnership.terminate(token, recorder)
                    } finally {
                        debugCaptureRegistrations.unregister(token, recorder)
                    }
                },
            )
        }

        if (
            publishVoiceAudioCapture(
                ownership = captureOwnership,
                setup = setup,
                task = job,
                cancelTask = Job::cancel,
                releaseRecorder = { it.releaseSafely() },
            ) == VoiceAudioCaptureStartOutcome.Started
        ) {
            onStarted(VoiceAudioCaptureAdmission(token, recorder))
            registerDebugCapture(token, recorder, onPcm16, onDebugInjectionComplete)
        }
    }

    override fun stopCapture() {
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        try {
            captureOwnership.stop()
        } finally {
            unregisterDebugCapture()
        }
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
        releaseInternal()
    }

    private fun releaseInternal() {
        val firstRelease = try {
            captureOwnership.release()
        } finally {
            unregisterDebugCapture()
        }
        if (!firstRelease) return
        playbackTracks.markReleased()
        playbackEventOwner.releasePlayback(playbackWriter::release)
        playbackTracks.releaseAll()
        routeController.close()
        scope.cancel()
    }

    private fun registerDebugCapture(
        token: VoiceAudioCaptureToken,
        recorder: AudioRecord,
        onPcm16: (ByteArray) -> Unit,
        onInjectionComplete: () -> Unit,
    ) {
        val registration = VoiceAudioDebugInjector.registerCaptureIfCurrent(
            onPcm16 = { buffer ->
                deliverInjectedCaptureBuffer(
                    token = token,
                    recorder = recorder,
                    buffer = buffer,
                    onPcm16 = onPcm16,
                )
            },
            onInjectionComplete = {
                captureOwnership.runCallbackIfCurrent(token, recorder) {
                    onInjectionComplete()
                }
            },
            isCurrent = { captureOwnership.isCurrent(token, recorder) },
        ) ?: return
        debugCaptureRegistrations.publish(token, recorder, registration) {
            captureOwnership.isCurrent(token, recorder)
        }
    }

    private fun unregisterDebugCapture() {
        debugCaptureRegistrations.unregister()
    }

    private fun deliverCaptureBuffer(
        token: VoiceAudioCaptureToken,
        recorder: AudioRecord,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        captureOwnership.runCallbackIfCurrent(token, recorder) {
            onPcm16(buffer)
        }
    }

    private fun deliverInjectedCaptureBuffer(
        token: VoiceAudioCaptureToken,
        recorder: AudioRecord,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        debugCaptureRegistrations.deliver(
            token = token,
            recorder = recorder,
            buffer = buffer,
            isCurrent = { captureOwnership.isCurrent(token, recorder) },
            onPcm16 = { admittedBuffer ->
                captureOwnership.runCallbackIfCurrent(token, recorder) {
                    onPcm16(admittedBuffer)
                }
            },
            terminate = { captureOwnership.terminate(token, recorder) },
            onFailure = { error ->
                Log.w(TAG, "Stopping capture after debug PCM injection callback failure", error)
            },
        )
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
            if (captureOwnership.isReleased()) null else errorHandler
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
