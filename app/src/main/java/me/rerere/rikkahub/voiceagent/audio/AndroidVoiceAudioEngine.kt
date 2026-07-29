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
import java.util.Base64
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
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbes

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
                retireCapture(admittedCapture)
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

internal class VoiceAutomationOutputTracker {
    private val lock = Any()
    private val metadataByCommand = mutableMapOf<PlaybackCommandId, OutputMetadata>()

    fun register(
        commandId: PlaybackCommandId,
        writerGeneration: WriterGeneration,
        byteCount: Int,
        nonSilent: Boolean,
        probe: VoiceAutomationAudioProbe,
    ) {
        synchronized(lock) {
            metadataByCommand[commandId] = OutputMetadata(
                writerGeneration = writerGeneration,
                byteCount = byteCount,
                nonSilent = nonSilent,
                probe = probe,
            )
        }
    }

    fun remove(commandId: PlaybackCommandId) {
        synchronized(lock) {
            metadataByCommand.remove(commandId)
        }
    }

    fun onDiagnostic(diagnostic: VoicePlaybackDiagnostic) {
        when (diagnostic) {
            is VoicePlaybackDiagnostic.ChunkQueued -> {
                val metadata = markQueued(diagnostic) ?: return
                metadata.probe.onOutputQueued(diagnostic.bytes)
                if (metadata.written) {
                    metadata.probe.onOutputWritten(
                        byteCount = diagnostic.bytes,
                        nonSilent = metadata.nonSilent,
                    )
                }
            }
            is VoicePlaybackDiagnostic.ChunkWritten -> {
                val metadata = markWritten(diagnostic) ?: return
                metadata.probe.onOutputWritten(
                    byteCount = diagnostic.bytes,
                    nonSilent = metadata.nonSilent,
                )
            }
            is VoicePlaybackDiagnostic.StaleChunkRejected ->
                remove(diagnostic.commandId)
            is VoicePlaybackDiagnostic.SinkStartFailed ->
                remove(diagnostic.commandId)
            is VoicePlaybackDiagnostic.SinkWriteFailed ->
                remove(diagnostic.commandId)
            is VoicePlaybackDiagnostic.PlaybackSuppressed ->
                removeBeforeGeneration(diagnostic.writerGeneration)
            is VoicePlaybackDiagnostic.SinkRetirementFailed,
            VoicePlaybackDiagnostic.Released,
            -> clear()
            is VoicePlaybackDiagnostic.MalformedChunk,
            is VoicePlaybackDiagnostic.SinkDrainFailed,
            is VoicePlaybackDiagnostic.PlaybackEventHandlerFailed,
            -> Unit
        }
    }

    private fun markQueued(diagnostic: VoicePlaybackDiagnostic.ChunkQueued): OutputMetadata? =
        synchronized(lock) {
            val metadata = metadataByCommand[diagnostic.commandId]
                ?.takeIf {
                    it.byteCount == diagnostic.bytes &&
                        it.writerGeneration == diagnostic.writerGeneration
                }
                ?: return@synchronized null
            metadata.queued = true
            if (metadata.written) {
                metadataByCommand.remove(diagnostic.commandId)
            }
            metadata
        }

    private fun removeBeforeGeneration(activeWriterGeneration: WriterGeneration) {
        synchronized(lock) {
            metadataByCommand.entries.removeAll { (_, metadata) ->
                metadata.writerGeneration < activeWriterGeneration
            }
        }
    }

    private fun markWritten(diagnostic: VoicePlaybackDiagnostic.ChunkWritten): OutputMetadata? =
        synchronized(lock) {
            val metadata = metadataByCommand[diagnostic.commandId]
                ?.takeIf {
                    it.byteCount == diagnostic.bytes &&
                        it.writerGeneration == diagnostic.writerGeneration
                }
                ?: return@synchronized null
            metadata.written = true
            if (metadata.queued) {
                metadataByCommand.remove(diagnostic.commandId)
                metadata
            } else {
                null
            }
        }

    private fun clear() {
        synchronized(lock) {
            metadataByCommand.clear()
        }
    }

    private class OutputMetadata(
        val writerGeneration: WriterGeneration,
        val byteCount: Int,
        val nonSilent: Boolean,
        val probe: VoiceAutomationAudioProbe,
    ) {
        var queued = false
        var written = false
    }
}

private sealed interface AndroidVoiceCaptureProducer {
    fun start()
    fun isStarted(): Boolean
    fun stop()
    fun release()

    class Microphone(
        val recorder: AudioRecord,
        val bufferSize: Int,
    ) : AndroidVoiceCaptureProducer {
        override fun start() = recorder.startRecording()
        override fun isStarted(): Boolean =
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        override fun stop() {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
        }
        override fun release() {
            runCatching { recorder.release() }
        }
    }

    class Fixture(
        val source: VoiceCaptureFixtureSource,
    ) : AndroidVoiceCaptureProducer {
        override fun start() = Unit
        override fun isStarted() = true
        override fun stop() = Unit
        override fun release() = Unit
    }
}

internal class AndroidVoiceAudioEngine(
    context: Context,
    routeOwner: VoiceAudioRouteOwner,
    private val captureSource: VoiceCaptureSource = VoiceCaptureSource.Microphone,
) : VoiceAudioEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val routeController = selectVoiceAudioRouteController(routeOwner) {
        AndroidDirectAudioRouteController(this.context, ::notifyAudioError)
    }
    private val captureOwnership = VoiceAudioCaptureOwnership<AndroidVoiceCaptureProducer, Job>(
        startRecorder = AndroidVoiceCaptureProducer::start,
        isRecorderRecording = AndroidVoiceCaptureProducer::isStarted,
        stopRecorder = AndroidVoiceCaptureProducer::stop,
        releaseRecorder = AndroidVoiceCaptureProducer::release,
        startTask = Job::start,
        cancelTask = { it.cancel() },
    )
    private val playbackTracks = AndroidVoicePlaybackTracks(
        audioAttributes = ::voiceAudioAttributes,
        onAssistantPlaybackError = ::notifyAudioError,
    )
    private val playbackEventOwner = VoicePlaybackEventOwner()
    private val automationAudioProbe = VoiceAutomationAudioProbes.shared
    private val automationOutputTracker = VoiceAutomationOutputTracker()
    private val playbackWriter = VoicePlaybackWriter(
        scope = scope,
        createSink = playbackTracks::createAssistantSinkOrNull,
        onDiagnostic = ::handlePlaybackDiagnostic,
        onPlaybackEvent = ::notifyPlaybackEvent,
    )
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
        onCaptureSourceComplete: () -> Unit,
    ) = runVoiceAudioCaptureStartOnDispatcher<AndroidVoiceCaptureProducer>(
        dispatcher = Dispatchers.IO,
        startCapture = { onStarted ->
            startCaptureInternal(onPcm16, onCaptureSourceComplete, onStarted)
        },
        retireCapture = { admission -> captureOwnership.abort(admission.token) },
    )

    private suspend fun startCaptureInternal(
        onPcm16: (ByteArray) -> Unit,
        onCaptureSourceComplete: () -> Unit,
        onStarted: (VoiceAudioCaptureAdmission<AndroidVoiceCaptureProducer>) -> Unit,
    ) {
        stopCaptureInternal()
        val setup = setupVoiceCaptureSource(
            source = captureSource,
            setupMicrophone = ::setupMicrophoneCapture,
            setupFixture = ::setupFixtureCapture,
        ) ?: return
        val token = setup.token
        val producer = setup.recorder

        val job = scope.launch(start = CoroutineStart.LAZY) {
            when (producer) {
                is AndroidVoiceCaptureProducer.Microphone ->
                    runMicrophoneCaptureLoop(token, producer, onPcm16)
                is AndroidVoiceCaptureProducer.Fixture ->
                    runFixtureCaptureLoop(token, producer, onPcm16, onCaptureSourceComplete)
            }
        }

        if (
            publishVoiceAudioCapture(
                ownership = captureOwnership,
                setup = setup,
                task = job,
                cancelTask = Job::cancel,
                releaseRecorder = AndroidVoiceCaptureProducer::release,
            ) == VoiceAudioCaptureStartOutcome.Started
        ) {
            onStarted(VoiceAudioCaptureAdmission(token, producer))
            (producer as? AndroidVoiceCaptureProducer.Fixture)?.source?.startInitial()
        }
    }

    override fun stopCapture() {
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        captureOwnership.stop()
    }

    override fun playPcm16(base64Pcm16: String, sessionId: Long?): Boolean {
        val metadata = VoiceAutomationAudioProbes.activeSharedOrNull()?.let { probe ->
            decodeOutputMetadataOrNull(base64Pcm16, probe)
        }
        val commandReservation = metadata?.let {
            playbackWriter.reserveCommand()
        }
        if (metadata != null) {
            automationOutputTracker.register(
                commandId = checkNotNull(commandReservation).commandId,
                writerGeneration = commandReservation.writerGeneration,
                byteCount = metadata.byteCount,
                nonSilent = metadata.nonSilent,
                probe = metadata.probe,
            )
        }
        val accepted = if (commandReservation == null) {
            playbackWriter.playBase64(base64Pcm16 = base64Pcm16, sessionId = sessionId)
        } else {
            playbackWriter.playBase64(
                base64Pcm16 = base64Pcm16,
                sessionId = sessionId,
                commandReservation = commandReservation,
            )
        }
        if (!accepted && commandReservation != null) {
            automationOutputTracker.remove(commandReservation.commandId)
        }
        return accepted
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
        val firstRelease = captureOwnership.release()
        if (!firstRelease) return
        playbackTracks.markReleased()
        playbackEventOwner.releasePlayback(playbackWriter::release)
        playbackTracks.releaseAll()
        routeController.close()
        captureSource.close()
        scope.cancel()
    }

    private suspend fun setupMicrophoneCapture(): VoiceAudioCaptureSetup<AndroidVoiceCaptureProducer>? {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Microphone permission is required")
        }
        return setupVoiceAudioCapture(
            ownership = captureOwnership,
            acquireRoute = routeController::acquireCapture,
            lookupBufferSize = ::captureBufferSize,
            createRecorder = { bufferSize ->
                AndroidVoiceCaptureProducer.Microphone(
                    recorder = createCaptureRecord(bufferSize),
                    bufferSize = bufferSize,
                )
            },
            configureRecorder = { routeLease, producer ->
                routeLease.configureRecorder(
                    (producer as AndroidVoiceCaptureProducer.Microphone).recorder,
                )
            },
            isRecorderInitialized = { producer ->
                (producer as AndroidVoiceCaptureProducer.Microphone).recorder.state ==
                    AudioRecord.STATE_INITIALIZED
            },
            releaseRecorder = AndroidVoiceCaptureProducer::release,
        )
    }

    private suspend fun setupFixtureCapture(
        source: VoiceCaptureFixtureSource,
    ): VoiceAudioCaptureSetup<AndroidVoiceCaptureProducer>? {
        val token = captureOwnership.reserve()
        val routeLease = try {
            currentCoroutineContext().ensureActive()
            routeController.acquireCapture()
        } catch (failure: Throwable) {
            throwVoiceAudioCaptureSetupFailure(failure, { captureOwnership.abort(token) })
        }
        if (!captureOwnership.publishRoute(token, routeLease)) {
            routeLease.retire()
            return null
        }
        try {
            routeLease.prepare()
        } catch (failure: Throwable) {
            throwVoiceAudioCaptureSetupFailure(failure, { captureOwnership.abort(token) })
        }
        return VoiceAudioCaptureSetup(
            token = token,
            bufferSize = 0,
            recorder = AndroidVoiceCaptureProducer.Fixture(source),
        )
    }

    private fun runMicrophoneCaptureLoop(
        token: VoiceAudioCaptureToken,
        producer: AndroidVoiceCaptureProducer.Microphone,
        onPcm16: (ByteArray) -> Unit,
    ) {
        var captureLevelChunks = 0
        runVoiceAudioCaptureLoop(
            bufferSize = producer.bufferSize,
            shouldContinue = { captureOwnership.isCurrent(token, producer) },
            read = { buffer -> producer.recorder.read(buffer, 0, buffer.size) },
            onPcm16 = { pcm16 ->
                captureLevelChunks += 1
                logCaptureLevelIfNeeded(chunk = captureLevelChunks, pcm16 = pcm16)
                deliverCaptureBuffer(token, producer, pcm16, onPcm16)
            },
            onReadException = { error ->
                if (captureOwnership.isCurrent(token, producer)) {
                    Log.w(TAG, "AudioRecord read failed", error)
                    notifyAudioError(
                        "AudioRecord read failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            },
            onNegativeRead = { read ->
                if (captureOwnership.isCurrent(token, producer)) {
                    val error = IllegalStateException("AudioRecord read error: $read")
                    Log.w(TAG, "Stopping capture after AudioRecord read failure", error)
                    notifyAudioError(error.message ?: error.javaClass.simpleName)
                }
            },
            onPcmCallbackException = { error ->
                if (captureOwnership.isCurrent(token, producer)) {
                    Log.w(TAG, "Stopping capture after PCM callback failure", error)
                }
            },
            onTerminated = { captureOwnership.terminate(token, producer) },
        )
    }

    private suspend fun runFixtureCaptureLoop(
        token: VoiceAudioCaptureToken,
        producer: AndroidVoiceCaptureProducer.Fixture,
        onPcm16: (ByteArray) -> Unit,
        onCaptureSourceComplete: () -> Unit,
    ) {
        try {
            producer.source.pump(
                onPcm16 = { buffer ->
                    deliverCaptureBuffer(token, producer, buffer, onPcm16)
                },
                onFixtureComplete = {
                    captureOwnership.runCallbackIfCurrent(token, producer, onCaptureSourceComplete)
                },
            )
        } finally {
            captureOwnership.terminate(token, producer)
        }
    }

    private fun deliverCaptureBuffer(
        token: VoiceAudioCaptureToken,
        producer: AndroidVoiceCaptureProducer,
        buffer: ByteArray,
        onPcm16: (ByteArray) -> Unit,
    ) {
        captureOwnership.runCallbackIfCurrent(token, producer) { onPcm16(buffer) }
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
        if (event is VoicePlaybackEvent.Drained) {
            automationAudioProbe.onOutputDrained()
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
        automationOutputTracker.onDiagnostic(diagnostic)
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

    private fun decodeOutputMetadataOrNull(
        base64Pcm16: String,
        probe: VoiceAutomationAudioProbe,
    ): OutputMetadata? =
        runCatching { Base64.getDecoder().decode(base64Pcm16) }
            .getOrNull()
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { pcm16 ->
                OutputMetadata(
                    byteCount = pcm16.size,
                    nonSilent = pcm16.any { it != 0.toByte() },
                    probe = probe,
                )
            }

    private class OutputMetadata(
        val byteCount: Int,
        val nonSilent: Boolean,
        val probe: VoiceAutomationAudioProbe,
    )

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
