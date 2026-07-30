package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.CleanupAttemptFailures
import me.rerere.rikkahub.voiceagent.CleanupAttemptOutcome
import me.rerere.rikkahub.voiceagent.JoinedCleanupOperation
import me.rerere.rikkahub.voiceagent.RouteOwnedManagedVoiceCallSession
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupMode
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupOperation
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupResult
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.VoiceAgentUiState
import me.rerere.rikkahub.voiceagent.VoiceAudioStatus
import me.rerere.rikkahub.voiceagent.VoiceDiagnosticLine
import me.rerere.rikkahub.voiceagent.VoiceSessionStatus
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureSource
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbes
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationCorrelationKind
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.core.context.GlobalContext

internal const val LIVEKIT_READY_TOPIC = "voice.ready.v1"
internal const val LIVEKIT_INTERRUPT_RPC = "voice.interrupt"
internal const val LIVEKIT_PERSISTENCE_RPC = "voice.persist.v1"

internal class LiveKitVoiceCallSession(
    private val details: LiveKitSessionDetails,
    traceId: String,
    private val room: LiveKitRoomFacade,
    private val routeLease: VoiceAgentRouteLease,
    private val scope: CoroutineScope,
    private val captureSource: VoiceCaptureSource = VoiceCaptureSource.Microphone,
    rpcMethods: Map<String, suspend (LiveKitRpcInvocation) -> String> = emptyMap(),
    persistenceHandler: (suspend (LiveKitRpcInvocation) -> String)? = null,
    private val persistenceOwner: LiveKitPersistenceOwner? = null,
    private val connectTimeoutMillis: Long = DEFAULT_LIVEKIT_CONNECT_TIMEOUT_MS,
    private val readyTimeoutMillis: Long = DEFAULT_LIVEKIT_READY_TIMEOUT_MS,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val automationRuntimeProvider: () -> VoiceAutomationRuntime? =
        ::liveKitAutomationRuntimeOrNull,
    private val automationAudioProbeProvider: () -> VoiceAutomationAudioProbe? =
        VoiceAutomationAudioProbes::activeSharedOrNull,
) : RouteOwnedManagedVoiceCallSession {
    private val registeredRpcMethods = buildMap {
        putAll(rpcMethods)
        persistenceHandler?.let { handler ->
            require(LIVEKIT_PERSISTENCE_RPC !in this) {
                "$LIVEKIT_PERSISTENCE_RPC is owned by the persistence handler"
            }
            put(LIVEKIT_PERSISTENCE_RPC, handler)
        }
    }
    private val activeTraceId = traceId
    private val mutableState = MutableStateFlow(VoiceAgentUiState(traceId = activeTraceId))
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val microphoneStateLock = Any()
    private val rpcAdmission = LiveKitRpcAdmission()
    private val ready = CompletableDeferred<Unit>()
    private val roomConnected = CompletableDeferred<Unit>()
    private val microphoneCommands = Channel<Unit>(capacity = Channel.CONFLATED)
    private var desiredMicrophoneEnabled = true
    private var wasReady = false
    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private var microphoneJob: Job? = null
    private var automationRuntime: VoiceAutomationRuntime? = null
    private var automationRunHash: String? = null
    private val automationCallBecameActive = AtomicBoolean(false)
    private val automationCallStoppedRecorded = AtomicBoolean(false)
    private var automationAudioActivation: AutoCloseable? = null

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readyTimeoutMillis > 0) { "readyTimeoutMillis must be positive" }
        require((persistenceHandler == null) == (persistenceOwner == null)) {
            "persistenceHandler and persistenceOwner must be provided together"
        }
    }

    override val state: StateFlow<VoiceAgentUiState> = mutableState.asStateFlow()
    override val routeMetadata = routeLease.metadata
    override val isRouteUsable: Boolean
        get() = !closed.get() && routeLease.isUsable
    override val cleanupOperation: VoiceAgentCleanupOperation = LiveKitCleanupOperation(
        routeLease = routeLease,
        requestClose = { requestCloseForCleanup() },
        connectionJob = { connectionJob },
        eventJob = { eventJob },
        microphoneJob = { microphoneJob },
        rpcAdmission = rpcAdmission,
        rpcMethods = registeredRpcMethods.keys,
        persistenceOwner = persistenceOwner,
        room = room,
        automationAudioActivation = { automationAudioActivation },
        captureSource = captureSource,
        recordCallStopped = ::recordAutomationCallStopped,
    )

    override fun start() {
        synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true) || closed.get()) return
            try {
                room.selectRemoteAudioParticipant(details.agentParticipantIdentity)
                activateAutomationAudioIfRequested()
            } catch (error: Throwable) {
                appendDiagnostic(
                    "livekit_automation_audio_failed",
                    error::class.simpleName ?: "unknown",
                )
                failExperimental("LiveKit experimental automation audio failed")
                return
            }
            registeredRpcMethods.forEach { (method, handler) ->
                room.registerRpcMethod(method) { invocation ->
                    rpcAdmission.runInbound { handler(invocation) }
                }
            }
            eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                room.events.collect(::handleRoomEvent)
            }
            if (closed.get()) return
            microphoneJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                publishMicrophoneCommands()
            }
            connectRoom()
        }
    }

    override fun interrupt() {
        rpcAdmission.launchOutbound(scope) {
            automationAudioProbeProvider()?.onInterruptionStarted()
            try {
                room.performRpc(
                    destination = details.agentParticipantIdentity,
                    method = LIVEKIT_INTERRUPT_RPC,
                    payload = "",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                appendDiagnostic("livekit_interrupt_failed", error::class.simpleName ?: "unknown")
            }
        }
    }

    override fun setMuted(value: Boolean) {
        synchronized(microphoneStateLock) {
            if (closed.get()) return
            desiredMicrophoneEnabled = !value
            mutableState.update { state ->
                state.copy(audio = if (value) VoiceAudioStatus.Muted else VoiceAudioStatus.Listening)
            }
            microphoneCommands.trySend(Unit)
        }
    }

    override fun reconnect() {
        if (!started.get() || closed.get()) return
        appendDiagnostic("livekit_native_reconnect_owned", "automatic")
    }

    override fun recordDiagnostic(name: String, detail: String) {
        appendDiagnostic(name, detail)
    }

    private fun connectRoom() {
        connectionJob = scope.launch {
            mutableState.update { it.copy(session = VoiceSessionStatus.ConnectingGemini) }
            try {
                withTimeout(connectTimeoutMillis) {
                    room.connect(details.livekitUrl, details.participantToken)
                }
                roomConnected.complete(Unit)
                microphoneCommands.trySend(Unit)
                withTimeout(readyTimeoutMillis) { ready.await() }
            } catch (timeout: TimeoutCancellationException) {
                failExperimental("LiveKit experimental voice connection timed out")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                failExperimental("LiveKit experimental voice connection failed")
            }
        }
    }

    private fun activateAutomationAudioIfRequested() {
        val runtime = automationRuntimeProvider() ?: return
        val status = runtime.status()
        if (
            status.state != VoiceAutomationRunState.Active ||
            status.requestedTransport != VoiceAgentTransport.LiveKitExperimental
        ) {
            return
        }
        val runHash = checkNotNull(status.runHash) {
            "Active LiveKit automation run has no run hash"
        }
        val activation = room.automationAudio.activate(runHash, captureSource, scope)
        try {
            details.automationCorrelations().forEach { correlation ->
                check(
                    runtime.recordIfActiveRun(
                        runHash = runHash,
                        event = VoiceAutomationEventInput(
                            name = VoiceAutomationEventName.CALL_START_REQUESTED,
                            observedTransport = VoiceAgentTransport.LiveKitExperimental,
                            correlationKind = correlation.kind,
                            correlationHash = correlation.hash,
                        ),
                    ),
                ) { "LiveKit automation run changed during call start" }
            }
            automationRuntime = runtime
            automationRunHash = runHash
            automationAudioActivation = activation
        } catch (error: Throwable) {
            activation.close()
            throw error
        }
    }

    private suspend fun publishMicrophoneCommands() {
        roomConnected.await()
        for (ignored in microphoneCommands) {
            while (true) {
                val requested = synchronized(microphoneStateLock) { desiredMicrophoneEnabled }
                try {
                    if (!room.setMicrophoneEnabled(requested)) {
                        appendDiagnostic("livekit_microphone_failed", "publication_rejected")
                        failExperimental("LiveKit experimental microphone control failed")
                        return
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    appendDiagnostic("livekit_microphone_failed", error::class.simpleName ?: "unknown")
                    failExperimental("LiveKit experimental microphone control failed")
                    return
                }
                while (microphoneCommands.tryReceive().isSuccess) {
                    // Requests are represented by desiredMicrophoneEnabled; discard stale wakeups.
                }
                if (requested == synchronized(microphoneStateLock) { desiredMicrophoneEnabled }) break
            }
        }
    }

    private fun handleRoomEvent(event: LiveKitRoomEvent) {
        if (closed.get()) return
        when (event) {
            LiveKitRoomEvent.Connected -> {
                if (wasReady) {
                    mutableState.update { it.copy(session = VoiceSessionStatus.Connected) }
                }
            }
            LiveKitRoomEvent.Reconnecting -> {
                recordOwnedAutomationEvent(
                    VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED),
                )
                mutableState.update { it.copy(session = VoiceSessionStatus.Reconnecting) }
            }
            LiveKitRoomEvent.Reconnected -> {
                markOwnedReconnectTransportRestored()
                mutableState.update {
                    it.copy(
                        session = if (wasReady) VoiceSessionStatus.Connected else VoiceSessionStatus.ConnectingGemini,
                    )
                }
            }
            is LiveKitRoomEvent.Data -> handleReady(event)
            is LiveKitRoomEvent.Failed -> failExperimental("LiveKit experimental voice connection failed")
            is LiveKitRoomEvent.Disconnected -> failExperimental("LiveKit experimental voice call disconnected")
            is LiveKitRoomEvent.ParticipantDisconnected -> {
                if (event.participantIdentity == details.agentParticipantIdentity) {
                    failExperimental("LiveKit experimental voice agent disconnected")
                }
            }
        }
    }

    private fun handleReady(event: LiveKitRoomEvent.Data) {
        if (event.participantIdentity != details.agentParticipantIdentity || event.topic != LIVEKIT_READY_TOPIC) return
        val message = parseLiveKitReadyMessage(event.payload) ?: return
        if (
            message.voiceSessionId != details.voiceSessionId ||
            message.eventIdHash != liveKitWorkerReadyHash(activeTraceId)
        ) return
        if (wasReady) return
        val recorded = recordOwnedAutomationEvent(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.CALL_ACTIVE,
                observedTransport = VoiceAgentTransport.LiveKitExperimental,
                correlationKind = VoiceAutomationCorrelationKind.WORKER_EVENT,
                correlationHash = message.eventIdHash,
            ),
        )
        if (recorded) automationCallBecameActive.set(true)
        wasReady = true
        (captureSource as? VoiceCaptureFixtureSource)?.startInitial()
        ready.complete(Unit)
        mutableState.update { it.copy(session = VoiceSessionStatus.Connected, error = null) }
    }

    private fun failExperimental(message: String) {
        if (!requestCloseForCleanup()) return
        mutableState.update {
            it.copy(
                session = VoiceSessionStatus.Error(message),
                error = message,
            )
        }
        scope.launch(cleanupDispatcher) {
            when (val result = cleanupOperation.run(VoiceAgentCleanupMode.Immediate)) {
                VoiceAgentCleanupResult.Completed -> appendDiagnostic("livekit_call_ended", "experimental_failure")
                is VoiceAgentCleanupResult.Failed -> appendDiagnostic(
                    "livekit_cleanup_failed",
                    result.error::class.simpleName ?: "unknown",
                )
            }
        }
    }

    private fun recordOwnedAutomationEvent(event: VoiceAutomationEventInput): Boolean {
        val runtime = automationRuntime ?: return false
        val runHash = automationRunHash ?: return false
        return runCatching {
            runtime.recordIfActiveRun(runHash = runHash, event = event)
        }.getOrDefault(false)
    }

    private fun markOwnedReconnectTransportRestored(): Boolean {
        val runtime = automationRuntime ?: return false
        val runHash = automationRunHash ?: return false
        return runCatching {
            runtime.markReconnectTransportRestored(runHash)
        }.getOrDefault(false)
    }

    private fun recordAutomationCallStopped() {
        if (
            !automationCallBecameActive.get() ||
            !automationCallStoppedRecorded.compareAndSet(false, true)
        ) {
            return
        }
        recordOwnedAutomationEvent(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.CALL_STOPPED,
                succeeded = true,
            ),
        )
    }

    private fun appendDiagnostic(name: String, detail: String) {
        mutableState.update { state ->
            state.copy(
                diagnostics = state.diagnostics + VoiceDiagnosticLine(
                    name = name,
                    detail = detail,
                    at = Instant.now().toString(),
                ),
            )
        }
    }

    private fun requestCloseForCleanup(): Boolean {
        return synchronized(lifecycleLock) {
            synchronized(microphoneStateLock) {
                rpcAdmission.close()
                closed.compareAndSet(false, true)
            }
        }
    }

    private companion object {
        const val DEFAULT_LIVEKIT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_LIVEKIT_READY_TIMEOUT_MS = 30_000L
    }
}

private class LiveKitCleanupOperation(
    private val routeLease: VoiceAgentRouteLease,
    private val requestClose: () -> Unit,
    private val connectionJob: () -> Job?,
    private val eventJob: () -> Job?,
    private val microphoneJob: () -> Job?,
    private val rpcAdmission: LiveKitRpcAdmission,
    rpcMethods: Set<String>,
    private val persistenceOwner: LiveKitPersistenceOwner?,
    private val room: LiveKitRoomFacade,
    private val automationAudioActivation: () -> AutoCloseable?,
    private val captureSource: VoiceCaptureSource,
    private val recordCallStopped: () -> Unit,
) : JoinedCleanupOperation() {
    private var routeCompleted = false
    private var connectionJobCompleted = false
    private var eventJobCompleted = false
    private var microphoneJobCompleted = false
    private var persistenceDrainCompleted = persistenceOwner == null
    private var persistenceDrainSkipped = false
    private var rpcWorkCompleted = false
    private var persistenceOwnerCompleted = persistenceOwner == null
    private var automationAudioCompleted = false
    private var captureSourceCompleted = false
    private val pendingRpcMethods = rpcMethods.toMutableSet()
    private var disconnectCompleted = false
    private var closeCompleted = false

    override suspend fun executeAttempt(mode: VoiceAgentCleanupMode): CleanupAttemptOutcome {
        val failures = CleanupAttemptFailures()
        failures.captureCallerCancellation()
        requestClose()
        try {
            withContext(NonCancellable) {
                automationAudioCompleted = cleanAutomationAudio(
                    automationAudioActivation(),
                    automationAudioCompleted,
                    failures,
                )
                captureSourceCompleted = cleanCaptureSource(captureSourceCompleted, failures)
                retireRoute(failures)
                drainPersistenceOwner(mode, failures)
                unregisterRpcMethods(
                    allowed = mode == VoiceAgentCleanupMode.Immediate || persistenceDrainCompleted,
                    failures = failures,
                )
                connectionJobCompleted = cleanJob(connectionJob(), connectionJobCompleted, failures)
                eventJobCompleted = cleanJob(eventJob(), eventJobCompleted, failures)
                microphoneJobCompleted = cleanJob(microphoneJob(), microphoneJobCompleted, failures)
                rpcWorkCompleted = cleanRpcWork(rpcWorkCompleted, failures)
                closePersistenceOwner(failures)
                disconnectRoom(failures)
                closeRoom(failures)
            }
        } catch (cancellation: CancellationException) {
            failures.add(cancellation)
        }
        return failures.outcome()
    }

    private fun retireRoute(failures: CleanupAttemptFailures) {
        if (routeCompleted) return
        try {
            routeLease.retire()
            routeCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun cleanAutomationAudio(
        activation: AutoCloseable?,
        completed: Boolean,
        failures: CleanupAttemptFailures,
    ): Boolean {
        if (completed) return true
        return try {
            activation?.close()
            true
        } catch (error: Throwable) {
            failures.add(error)
            false
        }
    }

    private fun cleanCaptureSource(
        completed: Boolean,
        failures: CleanupAttemptFailures,
    ): Boolean {
        if (completed) return true
        return try {
            captureSource.close()
            true
        } catch (error: Throwable) {
            failures.add(error)
            false
        }
    }

    private suspend fun cleanJob(
        job: Job?,
        completed: Boolean,
        failures: CleanupAttemptFailures,
    ): Boolean {
        if (completed) return true
        return try {
            job?.cancel()
            job?.join()
            true
        } catch (error: Throwable) {
            failures.add(error)
            false
        }
    }

    private suspend fun drainPersistenceOwner(
        mode: VoiceAgentCleanupMode,
        failures: CleanupAttemptFailures,
    ) {
        if (persistenceDrainCompleted || persistenceDrainSkipped) return
        if (mode == VoiceAgentCleanupMode.Immediate) {
            persistenceDrainSkipped = true
            return
        }
        try {
            persistenceOwner?.drain()
            persistenceDrainCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun unregisterRpcMethods(
        allowed: Boolean,
        failures: CleanupAttemptFailures,
    ) {
        if (!allowed) return
        pendingRpcMethods.toList().forEach { method ->
            try {
                room.unregisterRpcMethod(method)
                pendingRpcMethods.remove(method)
            } catch (error: Throwable) {
                failures.add(error)
            }
        }
    }

    private suspend fun cleanRpcWork(
        completed: Boolean,
        failures: CleanupAttemptFailures,
    ): Boolean {
        if (completed) return true
        return try {
            rpcAdmission.quiesce()
            true
        } catch (error: Throwable) {
            failures.add(error)
            false
        }
    }

    private fun closePersistenceOwner(failures: CleanupAttemptFailures) {
        if (
            persistenceOwnerCompleted ||
            !rpcWorkCompleted ||
            (!persistenceDrainCompleted && !persistenceDrainSkipped)
        ) return
        try {
            persistenceOwner?.close()
            persistenceOwnerCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun disconnectRoom(failures: CleanupAttemptFailures) {
        if (
            disconnectCompleted ||
            !automationAudioCompleted ||
            !jobsCompleted() ||
            !rpcWorkCompleted ||
            !persistenceOwnerCompleted ||
            pendingRpcMethods.isNotEmpty()
        ) return
        try {
            room.disconnect()
            disconnectCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun closeRoom(failures: CleanupAttemptFailures) {
        if (closeCompleted || !disconnectCompleted) return
        try {
            room.close()
            closeCompleted = true
            recordCallStopped()
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun jobsCompleted(): Boolean =
        connectionJobCompleted && eventJobCompleted && microphoneJobCompleted

    override fun hasUnfinishedStages(): Boolean =
        !automationAudioCompleted ||
            !captureSourceCompleted ||
            !routeCompleted ||
            !jobsCompleted() ||
            (!persistenceDrainCompleted && !persistenceDrainSkipped) ||
            !rpcWorkCompleted ||
            !persistenceOwnerCompleted ||
            pendingRpcMethods.isNotEmpty() ||
            !disconnectCompleted ||
            !closeCompleted
}

private class LiveKitRpcAdmission {
    private val lock = Any()
    private var accepting = true
    private val activeWork = mutableSetOf<LiveKitRpcWork>()

    fun close() {
        synchronized(lock) {
            accepting = false
        }
    }

    fun launchOutbound(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Boolean {
        lateinit var work: LiveKitRpcWork.Outbound
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (beginOutbound(work)) block()
        }
        work = LiveKitRpcWork.Outbound(job)
        job.invokeOnCompletion { complete(work) }
        val admitted = synchronized(lock) {
            if (!accepting) {
                false
            } else {
                activeWork += work
                if (job.isCompleted) activeWork.remove(work)
                true
            }
        }
        if (admitted) {
            job.start()
        } else {
            job.cancel()
        }
        return admitted
    }

    private fun beginOutbound(work: LiveKitRpcWork.Outbound): Boolean = synchronized(lock) {
        accepting && work in activeWork
    }

    suspend fun <T> runInbound(block: suspend () -> T): T {
        val work = LiveKitRpcWork.Inbound(CompletableDeferred())
        synchronized(lock) {
            if (!accepting) throw LiveKitRpcAdmissionClosedException()
            activeWork += work
        }
        return try {
            block()
        } finally {
            check(work.completion.complete(Unit)) { "Inbound LiveKit RPC work completed twice" }
            complete(work)
        }
    }

    suspend fun quiesce() {
        val admittedWork = synchronized(lock) {
            check(!accepting) { "LiveKit RPC work cannot quiesce while admission is open" }
            activeWork.toList()
        }
        admittedWork.forEach { work ->
            when (work) {
                is LiveKitRpcWork.Outbound -> {
                    work.job.cancel()
                    work.job.join()
                }
                is LiveKitRpcWork.Inbound -> work.completion.await()
            }
        }
    }

    private fun complete(work: LiveKitRpcWork) {
        synchronized(lock) {
            activeWork.remove(work)
        }
    }
}

private sealed interface LiveKitRpcWork {
    class Outbound(val job: Job) : LiveKitRpcWork
    class Inbound(val completion: CompletableDeferred<Unit>) : LiveKitRpcWork
}

private class LiveKitRpcAdmissionClosedException :
    IllegalStateException("LiveKit RPC admission is closed")

private fun liveKitAutomationRuntimeOrNull(): VoiceAutomationRuntime? =
    runCatching {
        GlobalContext.get().get<VoiceAutomationRuntime>()
    }.getOrNull()
