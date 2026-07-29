package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallOrchestratorStressTest {
    @Test
    fun `deterministic reducer sequences preserve ownership and complete every waiter`() {
        val seeds = listOf(1L, 7L, 42L, 20260719L)

        seeds.forEach { seed ->
            StressHarness(seed).run()
        }
    }

    @Test
    fun `ownership invariant rejects an active bundle with unrelated cleanup roots`() {
        val callCleanup = StressCleanupOperation(StressResourceState.Live)
        val sessionCleanup = StressCleanupOperation(StressResourceState.Live)
        val malformed = VoiceAgentCallState.Active(
            call = createActiveCall(orchestratorRequest("malformed"), callCleanup, sessionCleanup),
            sessionState = VoiceAgentUiState(session = VoiceSessionStatus.Connected),
        )

        val failure = assertThrows(AssertionError::class.java) {
            assertStressOwnerInvariant(malformed) { true }
        }

        assertTrue(failure.message.orEmpty().contains("independent resource owner"))
    }
}

private class StressHarness(seed: Long) {
    private val random = Random(seed)
    private val requests = List(3) { orchestratorRequest("stress-$seed-$it") }
    private val startReplies = mutableListOf<CompletableDeferred<VoiceAgentCallStartResult>>()
    private val endReplies = mutableListOf<CompletableDeferred<VoiceAgentCallEndResult>>()
    private val cancellationReplies = mutableListOf<CompletableDeferred<Throwable?>>()
    private val allCleanups = mutableListOf<StressCleanupOperation>()
    private val allOperations = mutableListOf<StressStartOperation>()
    private val allCalls = mutableListOf<ActiveVoiceAgentCall>()
    private val coverage = mutableSetOf<String>()
    private var state: VoiceAgentCallState = VoiceAgentCallState.Idle
    private var generatedEvents = 0

    fun run() {
        exerciseRequiredVariants()
        while (generatedEvents < EVENTS_PER_SEED) {
            randomEvent()
        }
        assertEquals(EVENTS_PER_SEED, generatedEvents)
        assertRequiredCoverage()
        drainToTerminalState()
        assertTrue(state is VoiceAgentCallState.Idle)
        assertTrue("all start replies must terminate", startReplies.all { it.isCompleted })
        assertTrue("all end replies must terminate", endReplies.all { it.isCompleted })
        assertTrue("all cancellation replies must terminate", cancellationReplies.all { it.isCompleted })
        assertRegisteredResourceFinality()
    }

    private fun exerciseRequiredVariants() {
        requestStart(requests[0], "different")
        requestStart(requests[0], "same")
        admit(current = true)
        cancelStart(current = false)
        cancelStart(current = true)
        cancelStart(current = true)
        cleanup(VoiceAgentCleanupResult.Completed, current = true)

        requestStart(requests[0], "different")
        admit(current = true)
        finish(StartOutcomeKind.Ready, current = true)
        session(SessionKind.Connected, current = true)
        session(SessionKind.ErrorUsable, current = true)
        session(SessionKind.Connected, current = false)
        requestStart(requests[0], "same")
        session(SessionKind.Ended, current = true)
        cleanup(VoiceAgentCleanupResult.Completed, current = true)

        requestStart(requests[0], "different")
        admit(current = true)
        finish(StartOutcomeKind.FailedClean, current = true)
        requestStart(requests[0], "different")
        admit(current = true)
        finish(StartOutcomeKind.FailedDirty, current = true)
        endCall()
        closeNow()
        cleanup(VoiceAgentCleanupResult.Failed(IllegalStateException("cleanup failed")), current = true)
        requestStart(requests[1], "different")
        cleanup(VoiceAgentCleanupResult.Completed, current = true)
        admit(current = true)
        finish(StartOutcomeKind.Cancelled, current = true)

        StartOutcomeKind.entries.forEach { finish(it, current = false) }
        admit(current = false)
        cleanup(VoiceAgentCleanupResult.Completed, current = false)
        closeNow()

        requestStart(requests[2], "different")
        admit(current = true)
        finish(StartOutcomeKind.Ready, current = true)
        session(SessionKind.ErrorUnusable, current = true)
        cleanup(VoiceAgentCleanupResult.Completed, current = true)
    }

    private fun randomEvent() {
        when (random.nextInt(8)) {
            0 -> randomStartRequest()
            1 -> admit(current = random.nextBoolean())
            2 -> cancelStart(current = random.nextBoolean())
            3 -> endCall()
            4 -> closeNow()
            5 -> finish(StartOutcomeKind.entries.random(random), current = random.nextBoolean())
            6 -> cleanup(randomCleanupResult(), current = random.nextBoolean())
            else -> session(SessionKind.entries.random(random), current = random.nextBoolean())
        }
    }

    private fun randomStartRequest() {
        val currentRequest = state.desiredRequest()
        val useSame = currentRequest != null && random.nextBoolean()
        val request = if (useSame) {
            currentRequest
        } else {
            requests.filterNot { it == currentRequest }.random(random)
        }
        requestStart(request, if (useSame) "same" else "different")
    }

    private fun requestStart(request: VoiceAgentCallRequest, variant: String) {
        val reply = newStartReply()
        coverage += "StartRequested"
        coverage += "StartRequested:$variant"
        dispatch(
            VoiceAgentCallEvent.StartRequested(
                PendingVoiceAgentStart(Any(), request, listOf(reply)),
            ),
        )
    }

    private fun admit(current: Boolean) {
        val admitting = state as? VoiceAgentCallState.Starting.Admitting
        val isCurrent = current && admitting != null
        val request = admitting?.pending?.request ?: requests.random(random)
        val operation = newOperation(request)
        coverage += "StartAdmitted"
        coverage += "StartAdmitted:${if (isCurrent) "current" else "stale"}"
        dispatch(
            VoiceAgentCallEvent.StartAdmitted(
                pendingToken = if (isCurrent) admitting.pending.token else Any(),
                operation = operation,
            ),
            stale = !isCurrent,
        )
    }

    private fun cancelStart(current: Boolean) {
        val ownedReply = state.startWaiters().firstOrNull { !it.isCompleted }
        val isCurrent = current && ownedReply != null
        val reply = if (isCurrent) checkNotNull(ownedReply) else newStartReply()
        val error = CancellationException("stress cancellation")
        reply.cancel(error)
        val completion = newCancellationReply()
        coverage += "StartCancelled"
        coverage += "StartCancelled:${if (isCurrent) "current" else "stale"}"
        dispatch(
            VoiceAgentCallEvent.StartCancelled(
                reply = reply,
                cancellation = PendingVoiceAgentCancellation(error, completion),
            ),
            stale = !isCurrent,
        )
    }

    private fun endCall() {
        val reply = newEndReply()
        coverage += "EndRequested"
        dispatch(VoiceAgentCallEvent.EndRequested(reply))
    }

    private fun closeNow() {
        coverage += "CloseNowRequested"
        dispatch(VoiceAgentCallEvent.CloseNowRequested)
    }

    private fun finish(kind: StartOutcomeKind, current: Boolean) {
        val running = state as? VoiceAgentCallState.Starting.Running
        val isCurrent = current && running != null
        val operation = if (isCurrent) running.operation else newOperation(requests.random(random))
        val outcome = outcome(kind, operation)
        when (kind) {
            StartOutcomeKind.Ready,
            StartOutcomeKind.FailedDirty,
            -> operation.cleanup.asStressCleanup().markLive()
            StartOutcomeKind.FailedClean,
            StartOutcomeKind.Cancelled,
            -> operation.cleanup.asStressCleanup().markAlreadyClean()
        }
        coverage += "StartFinished"
        coverage += "StartFinished:${if (isCurrent) "current" else "stale"}"
        coverage += "StartOutcome:$kind"
        dispatch(VoiceAgentCallEvent.StartFinished(operation, outcome), stale = !isCurrent)
    }

    private fun outcome(
        kind: StartOutcomeKind,
        operation: VoiceAgentStartOperation,
    ): VoiceAgentStartOutcome = when (kind) {
        StartOutcomeKind.Ready -> VoiceAgentStartOutcome.Ready(
            newActiveCall(operation.request, operation.cleanup as StressCleanupOperation),
            VoiceAgentUiState(session = VoiceSessionStatus.Connected),
        )
        StartOutcomeKind.FailedClean -> VoiceAgentStartOutcome.FailedClean(
            IllegalStateException("clean startup failure"),
        )
        StartOutcomeKind.FailedDirty -> VoiceAgentStartOutcome.FailedDirty(
            IllegalStateException("dirty startup failure"),
            operation.cleanup,
        )
        StartOutcomeKind.Cancelled -> VoiceAgentStartOutcome.Cancelled
    }

    private fun cleanup(result: VoiceAgentCleanupResult, current: Boolean) {
        val stopping = state as? VoiceAgentCallState.Stopping
        val isCurrent = current && stopping != null
        val cleanup = if (isCurrent) {
            stopping.cleanup
        } else {
            newCleanup(
                if (result == VoiceAgentCleanupResult.Completed) {
                    StressResourceState.AlreadyClean
                } else {
                    StressResourceState.TerminalWithoutResources
                },
            )
        }
        if (isCurrent && result == VoiceAgentCleanupResult.Completed) {
            (cleanup as StressCleanupOperation).markCleaned()
        }
        coverage += "CleanupFinished"
        coverage += "CleanupFinished:${if (isCurrent) "current" else "stale"}"
        coverage += when (result) {
            VoiceAgentCleanupResult.Completed -> "CleanupResult:Completed"
            is VoiceAgentCleanupResult.Failed -> "CleanupResult:Failed"
        }
        dispatch(VoiceAgentCallEvent.CleanupFinished(cleanup, result), stale = !isCurrent)
    }

    private fun session(kind: SessionKind, current: Boolean) {
        val active = state as? VoiceAgentCallState.Active
        val isCurrent = current && active != null
        val call = if (isCurrent) active.call else newAlreadyCleanCall(requests.random(random))
        val routeUsable = kind != SessionKind.ErrorUnusable
        val sessionState = VoiceAgentUiState(
            session = when (kind) {
                SessionKind.Connected -> VoiceSessionStatus.Connected
                SessionKind.ErrorUsable,
                SessionKind.ErrorUnusable,
                -> VoiceSessionStatus.Error("stress session failure")
                SessionKind.Ended -> VoiceSessionStatus.Ended
            },
        )
        coverage += "SessionStateChanged"
        coverage += "SessionStateChanged:${if (isCurrent) "current" else "stale"}"
        coverage += "Session:$kind"
        dispatch(
            VoiceAgentCallEvent.SessionStateChanged(call, sessionState, routeUsable),
            stale = !isCurrent,
        )
    }

    private fun dispatch(event: VoiceAgentCallEvent, stale: Boolean = false, generated: Boolean = true) {
        assertEventObjectsRegistered(event)
        val before = state
        val beforeCleanup = before.soleRootCleanupOrNull()
        val transition = reduceVoiceAgentCallState(before, event)
        if (stale) {
            assertSame("stale identity changed state for $event", before, transition.state)
        }
        state = transition.state
        markStateResourcesLive()
        assertOwnerInvariant()
        val afterCleanup = state.soleRootCleanupOrNull()
        if (beforeCleanup != null && afterCleanup != null) {
            assertSame("cleanup identity changed without release", beforeCleanup, afterCleanup)
        }
        applyCompletionEffects(transition.effects)
        if (generated) generatedEvents += 1
    }

    private fun applyCompletionEffects(effects: List<VoiceAgentCallEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is VoiceAgentCallEffect.CompleteStarts -> effect.replies.forEach { it.complete(effect.result) }
                is VoiceAgentCallEffect.CompleteStartsWithCancellation -> {
                    effect.replies.forEach { it.completeExceptionally(effect.error) }
                }
                is VoiceAgentCallEffect.CompleteEnds -> effect.replies.forEach { it.complete(effect.result) }
                is VoiceAgentCallEffect.CompleteCancellations -> {
                    effect.cancellations.forEach { it.completion.complete(effect.cleanupFailure) }
                }
                is VoiceAgentCallEffect.RunCleanup -> {
                    assertNotNull(effect.cleanup.token)
                    if (effect.cleanup !== state.soleRootCleanupOrNull()) {
                        (effect.cleanup as? StressCleanupOperation)?.markCleaned()
                    }
                }
                is VoiceAgentCallEffect.AdmitStart,
                is VoiceAgentCallEffect.ApplyCallStatus,
                is VoiceAgentCallEffect.ApplySessionState,
                is VoiceAgentCallEffect.CancelStart,
                is VoiceAgentCallEffect.LaunchStart,
                is VoiceAgentCallEffect.Reconnect,
                is VoiceAgentCallEffect.RecordDiagnostic,
                -> Unit
            }
        }
    }

    private fun assertOwnerInvariant() {
        assertStressOwnerInvariant(state, ::isRegistered)
    }

    private fun drainToTerminalState() {
        repeat(MAX_DRAIN_EVENTS) {
            when (state) {
                VoiceAgentCallState.Idle -> return
                is VoiceAgentCallState.Starting.Admitting -> admitForDrain()
                is VoiceAgentCallState.Starting.Running -> finishForDrain()
                is VoiceAgentCallState.Active -> endForDrain()
                is VoiceAgentCallState.Stopping -> cleanupForDrain()
                is VoiceAgentCallState.CleanupFailed -> closeForDrain()
            }
        }
        throw AssertionError("stress sequence did not reach Idle: $state")
    }

    private fun admitForDrain() {
        val admitting = state as VoiceAgentCallState.Starting.Admitting
        dispatch(
            VoiceAgentCallEvent.StartAdmitted(
                admitting.pending.token,
                newOperation(admitting.pending.request),
            ),
            generated = false,
        )
    }

    private fun finishForDrain() {
        val running = state as VoiceAgentCallState.Starting.Running
        (running.operation.cleanup as StressCleanupOperation).markAlreadyClean()
        dispatch(
            VoiceAgentCallEvent.StartFinished(
                running.operation,
                VoiceAgentStartOutcome.FailedClean(IllegalStateException("terminal drain")),
            ),
            generated = false,
        )
    }

    private fun endForDrain() {
        val reply = newEndReply()
        dispatch(VoiceAgentCallEvent.EndRequested(reply), generated = false)
    }

    private fun cleanupForDrain() {
        val stopping = state as VoiceAgentCallState.Stopping
        (stopping.cleanup as StressCleanupOperation).markCleaned()
        dispatch(
            VoiceAgentCallEvent.CleanupFinished(stopping.cleanup, VoiceAgentCleanupResult.Completed),
            generated = false,
        )
    }

    private fun closeForDrain() {
        dispatch(VoiceAgentCallEvent.CloseNowRequested, generated = false)
    }

    private fun newOperation(request: VoiceAgentCallRequest): StressStartOperation =
        StressStartOperation(request, newCleanup()).also(allOperations::add)

    private fun newCleanup(
        initialState: StressResourceState = StressResourceState.Allocated,
    ): StressCleanupOperation = StressCleanupOperation(initialState).also(allCleanups::add)

    private fun newActiveCall(
        request: VoiceAgentCallRequest,
        callCleanup: StressCleanupOperation,
    ): ActiveVoiceAgentCall {
        val sessionCleanup = newCleanup()
        callCleanup.containsResource(sessionCleanup)
        callCleanup.markLive()
        return createActiveCall(request, callCleanup, sessionCleanup).also(allCalls::add)
    }

    private fun newAlreadyCleanCall(request: VoiceAgentCallRequest): ActiveVoiceAgentCall {
        val callCleanup = newCleanup(StressResourceState.AlreadyClean)
        val sessionCleanup = newCleanup(StressResourceState.AlreadyClean)
        callCleanup.containsResource(sessionCleanup)
        return createActiveCall(request, callCleanup, sessionCleanup).also(allCalls::add)
    }

    private fun newStartReply(): CompletableDeferred<VoiceAgentCallStartResult> =
        CompletableDeferred<VoiceAgentCallStartResult>().also(startReplies::add)

    private fun newEndReply(): CompletableDeferred<VoiceAgentCallEndResult> =
        CompletableDeferred<VoiceAgentCallEndResult>().also(endReplies::add)

    private fun newCancellationReply(): CompletableDeferred<Throwable?> =
        CompletableDeferred<Throwable?>().also(cancellationReplies::add)

    private fun markStateResourcesLive() {
        state.ownershipCleanupReferences().forEach { reference -> reference.cleanup.markLive() }
    }

    private fun assertEventObjectsRegistered(event: VoiceAgentCallEvent) {
        when (event) {
            is VoiceAgentCallEvent.StartRequested -> event.pending.replies.forEach { reply ->
                assertTrue("unregistered start reply", startReplies.any { it === reply })
            }
            is VoiceAgentCallEvent.StartAdmitted -> {
                assertTrue("unregistered start operation", allOperations.any { it === event.operation })
            }
            is VoiceAgentCallEvent.StartCancelled -> {
                assertTrue("unregistered cancelled start reply", startReplies.any { it === event.reply })
                assertTrue(
                    "unregistered cancellation completion",
                    cancellationReplies.any { it === event.cancellation.completion },
                )
            }
            is VoiceAgentCallEvent.EndRequested -> {
                assertTrue("unregistered end reply", endReplies.any { it === event.reply })
            }
            is VoiceAgentCallEvent.StartFinished -> {
                assertTrue("unregistered finished operation", allOperations.any { it === event.operation })
                when (val outcome = event.outcome) {
                    is VoiceAgentStartOutcome.Ready -> {
                        assertTrue("unregistered ready call", allCalls.any { it === outcome.call })
                    }
                    is VoiceAgentStartOutcome.FailedDirty -> {
                        assertTrue("unregistered dirty cleanup", isRegistered(outcome.cleanup))
                    }
                    is VoiceAgentStartOutcome.CancelledDirty -> {
                        assertTrue("unregistered cancelled cleanup", isRegistered(outcome.cleanup))
                    }
                    is VoiceAgentStartOutcome.FailedClean,
                    is VoiceAgentStartOutcome.CancelledClean,
                    VoiceAgentStartOutcome.Cancelled,
                    -> Unit
                }
            }
            is VoiceAgentCallEvent.CleanupFinished -> {
                assertTrue("unregistered cleanup completion", isRegistered(event.cleanup))
            }
            is VoiceAgentCallEvent.SessionStateChanged -> {
                assertTrue("unregistered session call", allCalls.any { it === event.call })
            }
            VoiceAgentCallEvent.CloseNowRequested -> Unit
        }
    }

    private fun isRegistered(cleanup: VoiceAgentCleanupOperation): Boolean =
        allCleanups.any { it === cleanup }

    private fun assertRegisteredResourceFinality() {
        val currentActive = (state as? VoiceAgentCallState.Active)?.call
        val currentRunning = (state as? VoiceAgentCallState.Starting.Running)?.operation
        allCleanups.forEach { cleanup ->
            val retainedByActive = currentActive?.let { call ->
                cleanup === call.cleanup || (call.cleanup as StressCleanupOperation).contains(cleanup)
            } ?: false
            val retainedByStartup = currentRunning?.cleanup === cleanup
            assertTrue(
                "registered cleanup ${cleanup.token} is neither terminal nor the exact current owner",
                cleanup.isTerminal || retainedByActive || retainedByStartup,
            )
        }
        allOperations.forEach { operation ->
            assertTrue(
                "operation cleanup is neither terminal nor current",
                operation.cleanup.asStressCleanup().isTerminal || operation === currentRunning,
            )
            assertTrue("operation call job must be terminal", operation.phase.callJob.isCompleted)
        }
        allCalls.forEach { call ->
            assertTrue(
                "call cleanup is neither terminal nor current",
                call.cleanup.asStressCleanup().isTerminal || call === currentActive,
            )
            assertTrue("call job must be terminal", call.callJob.isCompleted)
            assertTrue("collector must be terminal", call.collector.isCompleted)
        }
    }

    private fun assertRequiredCoverage() {
        val required = buildSet {
            addAll(EVENT_NAMES)
            addAll(IDENTITY_EVENTS.flatMap { event -> listOf("$event:current", "$event:stale") })
            addAll(listOf("StartRequested:same", "StartRequested:different"))
            addAll(StartOutcomeKind.entries.map { "StartOutcome:$it" })
            addAll(listOf("CleanupResult:Completed", "CleanupResult:Failed"))
            addAll(SessionKind.entries.map { "Session:$it" })
        }
        assertEquals("missing deterministic stress coverage", emptySet<String>(), required - coverage)
    }

    private fun randomCleanupResult(): VoiceAgentCleanupResult = if (random.nextBoolean()) {
        VoiceAgentCleanupResult.Completed
    } else {
        VoiceAgentCleanupResult.Failed(IllegalStateException("random cleanup failure"))
    }
}

private enum class StartOutcomeKind {
    Ready,
    FailedClean,
    FailedDirty,
    Cancelled,
}

private enum class SessionKind {
    Connected,
    ErrorUsable,
    ErrorUnusable,
    Ended,
}

private class StressStartOperation(
    override val request: VoiceAgentCallRequest,
    override val cleanup: VoiceAgentCleanupOperation,
) : VoiceAgentStartOperation {
    override val token: Any = Any()
    private val callJob = completedStressJob()
    override val phase: VoiceAgentStartPhase = VoiceAgentStartPhase.PreparingRoute(
        request = request,
        callScope = CoroutineScope(callJob),
        callJob = callJob,
    )

    override fun start() = Unit

    override fun cancel() = Unit
}

private enum class StressResourceState {
    Allocated,
    Live,
    AlreadyClean,
    Cleaned,
    TerminalWithoutResources,
}

private class StressCleanupOperation(
    private var resourceState: StressResourceState,
) : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    private val containedResources = mutableListOf<StressCleanupOperation>()
    val isTerminal: Boolean
        get() = resourceState == StressResourceState.AlreadyClean ||
            resourceState == StressResourceState.Cleaned ||
            resourceState == StressResourceState.TerminalWithoutResources

    fun containsResource(cleanup: StressCleanupOperation) {
        check(containedResources.none { it === cleanup })
        containedResources += cleanup
        when (resourceState) {
            StressResourceState.Live -> cleanup.markLive()
            StressResourceState.AlreadyClean -> cleanup.markAlreadyClean()
            StressResourceState.Cleaned -> cleanup.markCleaned()
            StressResourceState.TerminalWithoutResources -> cleanup.markTerminalWithoutResources()
            StressResourceState.Allocated -> Unit
        }
    }

    fun contains(cleanup: StressCleanupOperation): Boolean =
        containedResources.any { child -> child === cleanup || child.contains(cleanup) }

    fun markLive() {
        check(!isTerminal) { "terminal cleanup cannot reacquire resources" }
        resourceState = StressResourceState.Live
        containedResources.forEach(StressCleanupOperation::markLive)
    }

    fun markAlreadyClean() {
        resourceState = StressResourceState.AlreadyClean
        containedResources.forEach(StressCleanupOperation::markAlreadyClean)
    }

    fun markCleaned() {
        resourceState = StressResourceState.Cleaned
        containedResources.forEach(StressCleanupOperation::markCleaned)
    }

    private fun markTerminalWithoutResources() {
        resourceState = StressResourceState.TerminalWithoutResources
        containedResources.forEach(StressCleanupOperation::markTerminalWithoutResources)
    }

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult =
        VoiceAgentCleanupResult.Completed
}

private class StressRouteOwnedSession(
    override val cleanupOperation: VoiceAgentCleanupOperation,
) : RouteOwnedManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState(session = VoiceSessionStatus.Connected))
    override val routeMetadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isRouteUsable = true

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
}

private fun createActiveCall(
    request: VoiceAgentCallRequest,
    callCleanup: VoiceAgentCleanupOperation,
    sessionCleanup: VoiceAgentCleanupOperation,
): ActiveVoiceAgentCall {
    val session = StressRouteOwnedSession(sessionCleanup)
    val callJob = completedStressJob()
    return ActiveVoiceAgentCall(
        token = Any(),
        request = request,
        route = session.routeMetadata,
        session = session,
        callScope = CoroutineScope(callJob),
        callJob = callJob,
        collector = completedStressJob(),
        cleanup = callCleanup,
    )
}

private fun completedStressJob(): Job = CompletableDeferred<Unit>().apply { complete(Unit) }

private fun VoiceAgentCallState.desiredRequest(): VoiceAgentCallRequest? = when (this) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Stopping.ForEnd,
    is VoiceAgentCallState.CleanupFailed,
    -> null
    is VoiceAgentCallState.Starting -> pending.request
    is VoiceAgentCallState.Active -> call.request
    is VoiceAgentCallState.Stopping.ForReplacement -> pending.request
}

private fun VoiceAgentCallState.startWaiters(): List<CompletableDeferred<VoiceAgentCallStartResult>> = when (this) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Active,
    is VoiceAgentCallState.CleanupFailed,
    -> emptyList()
    is VoiceAgentCallState.Starting -> pending.replies
    is VoiceAgentCallState.Stopping.ForEnd -> supersededStarts
    is VoiceAgentCallState.Stopping.ForReplacement -> supersededStarts + pending.replies
}

private data class StressOwnershipReference(
    val label: String,
    val cleanup: StressCleanupOperation,
)

private fun VoiceAgentCallState.ownershipCleanupReferences(): List<StressOwnershipReference> = when (this) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Starting.Admitting,
    -> emptyList()
    is VoiceAgentCallState.Starting.Running -> listOf(
        StressOwnershipReference("running operation", operation.cleanup.asStressCleanup()),
    )
    is VoiceAgentCallState.Active -> listOf(
        StressOwnershipReference("active call", call.cleanup.asStressCleanup()),
        StressOwnershipReference("active session", call.session.cleanupOperation.asStressCleanup()),
    )
    is VoiceAgentCallState.Stopping -> listOf(
        StressOwnershipReference("stopping cleanup", cleanup.asStressCleanup()),
    )
    is VoiceAgentCallState.CleanupFailed -> listOf(
        StressOwnershipReference("cleanup-failed owner", cleanup.asStressCleanup()),
    )
}

private fun assertStressOwnerInvariant(
    state: VoiceAgentCallState,
    isRegistered: (VoiceAgentCleanupOperation) -> Boolean,
) {
    val references = state.ownershipCleanupReferences()
    val distinctTokens = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    references.forEach { reference ->
        assertTrue("state cleanup was not registered: ${reference.label}", isRegistered(reference.cleanup))
        distinctTokens += reference.cleanup.token
    }
    val rootOwners = references.filter { candidate ->
        references.none { other ->
            other !== candidate && other.cleanup.contains(candidate.cleanup)
        }
    }
    val distinctRoots = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    rootOwners.forEach { distinctRoots += it.cleanup.token }
    assertTrue("state published more than one independent resource owner", distinctRoots.size <= 1)

    when (val current = state) {
        VoiceAgentCallState.Idle,
        is VoiceAgentCallState.Starting.Admitting,
        -> assertEquals(0, distinctTokens.size)
        is VoiceAgentCallState.Starting.Running -> {
            assertEquals(1, distinctTokens.size)
            assertSame(current.operation.phase.callJob, current.operation.phase.callScope.coroutineContext[Job])
        }
        is VoiceAgentCallState.Active -> {
            assertEquals(2, distinctTokens.size)
            val sessionCleanup = current.call.session.cleanupOperation.asStressCleanup()
            val callCleanup = current.call.cleanup.asStressCleanup()
            assertNotSame(callCleanup, sessionCleanup)
            assertTrue("active cleanup must contain the session cleanup", callCleanup.contains(sessionCleanup))
            assertNotNull(current.call.route)
            assertNotNull(current.call.session)
            assertNotNull(current.call.callScope)
            assertNotNull(current.call.callJob)
            assertNotNull(current.call.collector)
            assertSame(current.call.callJob, current.call.callScope.coroutineContext[Job])
            assertEquals(current.call.route, current.call.session.routeMetadata)
        }
        is VoiceAgentCallState.Stopping,
        is VoiceAgentCallState.CleanupFailed,
        -> assertEquals(1, distinctTokens.size)
    }
}

private fun VoiceAgentCallState.soleRootCleanupOrNull(): VoiceAgentCleanupOperation? =
    ownershipCleanupReferences()
        .filter { candidate ->
            ownershipCleanupReferences().none { other ->
                other !== candidate && other.cleanup.contains(candidate.cleanup)
            }
        }
        .singleOrNull()
        ?.cleanup

private fun VoiceAgentCleanupOperation.asStressCleanup(): StressCleanupOperation =
    this as StressCleanupOperation

private const val EVENTS_PER_SEED = 250
private const val MAX_DRAIN_EVENTS = 16
private val EVENT_NAMES = setOf(
    "StartRequested",
    "StartAdmitted",
    "StartCancelled",
    "EndRequested",
    "CloseNowRequested",
    "StartFinished",
    "CleanupFinished",
    "SessionStateChanged",
)
private val IDENTITY_EVENTS = setOf(
    "StartAdmitted",
    "StartCancelled",
    "StartFinished",
    "CleanupFinished",
    "SessionStateChanged",
)
