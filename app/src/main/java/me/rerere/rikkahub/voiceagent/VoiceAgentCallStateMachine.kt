package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import kotlin.uuid.Uuid

internal data class VoiceAgentCallRequest(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val transport: VoiceAgentTransport,
    val captureFixtureToken: String? = null,
)

internal data class ActiveVoiceAgentIdentity(
    val conversationId: Uuid,
    val transport: VoiceAgentTransport,
)

internal sealed interface VoiceAgentCallStartResult {
    data class Active(val route: VoiceAgentRouteMetadata) : VoiceAgentCallStartResult
    data object Superseded : VoiceAgentCallStartResult
    data class Failed(val error: Throwable) : VoiceAgentCallStartResult
}

internal sealed interface VoiceAgentCallEndResult {
    data object Completed : VoiceAgentCallEndResult
    data class Failed(val error: Throwable) : VoiceAgentCallEndResult
}

internal sealed interface VoiceAgentCallLifecycle {
    data object Idle : VoiceAgentCallLifecycle
    data class Starting(val conversationId: Uuid) : VoiceAgentCallLifecycle
    data class Active(val conversationId: Uuid) : VoiceAgentCallLifecycle
    data class Stopping(val conversationId: Uuid?) : VoiceAgentCallLifecycle
    data class CleanupFailed(val error: Throwable) : VoiceAgentCallLifecycle
}

internal interface VoiceAgentStartOperation {
    val token: Any
    val request: VoiceAgentCallRequest
    val phase: VoiceAgentStartPhase
    val cleanup: VoiceAgentCleanupOperation
    fun start()
    fun cancel()
}

internal sealed interface VoiceAgentStartPhase {
    val request: VoiceAgentCallRequest
    val callScope: CoroutineScope
    val callJob: Job

    data class Admitted(
        override val request: VoiceAgentCallRequest,
    ) : VoiceAgentStartPhase {
        override val callScope: CoroutineScope
            get() = error("Admitted startup has no call scope")
        override val callJob: Job
            get() = error("Admitted startup has no call job")
    }

    data class PreparingRoute(
        override val request: VoiceAgentCallRequest,
        override val callScope: CoroutineScope,
        override val callJob: Job,
    ) : VoiceAgentStartPhase

    data class CreatingSession(
        override val request: VoiceAgentCallRequest,
        override val callScope: CoroutineScope,
        override val callJob: Job,
    ) : VoiceAgentStartPhase

    data class StartingSession(
        override val request: VoiceAgentCallRequest,
        override val callScope: CoroutineScope,
        override val callJob: Job,
        val session: RouteOwnedManagedVoiceCallSession,
    ) : VoiceAgentStartPhase
}

internal sealed interface VoiceAgentStartOutcome {
    data class Ready(
        val call: ActiveVoiceAgentCall,
        val sessionState: VoiceAgentUiState,
    ) : VoiceAgentStartOutcome
    data class FailedClean(val error: Throwable) : VoiceAgentStartOutcome

    data class FailedDirty(
        val error: Throwable,
        val cleanup: VoiceAgentCleanupOperation,
    ) : VoiceAgentStartOutcome

    data class CancelledClean(val error: CancellationException) : VoiceAgentStartOutcome

    data class CancelledDirty(
        val error: CancellationException,
        val cleanup: VoiceAgentCleanupOperation,
    ) : VoiceAgentStartOutcome

    data object Cancelled : VoiceAgentStartOutcome
}

internal data class ActiveVoiceAgentCall(
    val token: Any,
    val request: VoiceAgentCallRequest,
    val route: VoiceAgentRouteMetadata,
    val session: RouteOwnedManagedVoiceCallSession,
    val callScope: CoroutineScope,
    val callJob: Job,
    val collector: Job,
    val cleanup: VoiceAgentCleanupOperation,
)

internal data class PendingVoiceAgentStart(
    val token: Any,
    val request: VoiceAgentCallRequest,
    val replies: List<CompletableDeferred<VoiceAgentCallStartResult>>,
) {
    init {
        require(replies.isNotEmpty()) { "Pending voice-agent starts require at least one reply" }
    }
}

internal data class PendingVoiceAgentCancellation(
    val error: CancellationException,
    val completion: CompletableDeferred<Throwable?>,
)

internal sealed interface VoiceAgentCallState {
    data object Idle : VoiceAgentCallState

    sealed interface Starting : VoiceAgentCallState {
        val pending: PendingVoiceAgentStart

        data class Admitting(
            override val pending: PendingVoiceAgentStart,
        ) : Starting

        data class Running(
            override val pending: PendingVoiceAgentStart,
            val operation: VoiceAgentStartOperation,
        ) : Starting
    }

    data class Active(
        val call: ActiveVoiceAgentCall,
        val sessionState: VoiceAgentUiState,
    ) : VoiceAgentCallState

    sealed interface Stopping : VoiceAgentCallState {
        val cleanup: VoiceAgentCleanupOperation
        val supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>
        val ends: List<CompletableDeferred<VoiceAgentCallEndResult>>
        val cancellations: List<PendingVoiceAgentCancellation>

        data class ForEnd(
            override val cleanup: VoiceAgentCleanupOperation,
            override val supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>,
            override val ends: List<CompletableDeferred<VoiceAgentCallEndResult>>,
            override val cancellations: List<PendingVoiceAgentCancellation>,
        ) : Stopping

        data class ForReplacement(
            override val cleanup: VoiceAgentCleanupOperation,
            val pending: PendingVoiceAgentStart,
            override val supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>,
            override val ends: List<CompletableDeferred<VoiceAgentCallEndResult>>,
            override val cancellations: List<PendingVoiceAgentCancellation>,
        ) : Stopping
    }

    data class CleanupFailed(
        val cleanup: VoiceAgentCleanupOperation,
        val error: Throwable,
    ) : VoiceAgentCallState
}

internal val VoiceAgentCallState.lifecycle: VoiceAgentCallLifecycle
    get() = when (this) {
        VoiceAgentCallState.Idle -> VoiceAgentCallLifecycle.Idle
        is VoiceAgentCallState.Starting -> VoiceAgentCallLifecycle.Starting(pending.request.conversationId)
        is VoiceAgentCallState.Active -> VoiceAgentCallLifecycle.Active(call.request.conversationId)
        is VoiceAgentCallState.Stopping.ForEnd -> VoiceAgentCallLifecycle.Stopping(null)
        is VoiceAgentCallState.Stopping.ForReplacement -> {
            VoiceAgentCallLifecycle.Stopping(pending.request.conversationId)
        }
        is VoiceAgentCallState.CleanupFailed -> VoiceAgentCallLifecycle.CleanupFailed(error)
    }

internal val VoiceAgentCallState.activeIdentity: ActiveVoiceAgentIdentity?
    get() = (this as? VoiceAgentCallState.Active)?.call?.request?.let { request ->
        ActiveVoiceAgentIdentity(request.conversationId, request.transport)
    }

internal sealed interface VoiceAgentCallEvent {
    data class StartRequested(val pending: PendingVoiceAgentStart) : VoiceAgentCallEvent

    data class StartAdmitted(
        val pendingToken: Any,
        val operation: VoiceAgentStartOperation,
    ) : VoiceAgentCallEvent

    data class StartCancelled(
        val reply: CompletableDeferred<VoiceAgentCallStartResult>,
        val cancellation: PendingVoiceAgentCancellation,
    ) : VoiceAgentCallEvent

    data class EndRequested(
        val reply: CompletableDeferred<VoiceAgentCallEndResult>,
    ) : VoiceAgentCallEvent

    data object CloseNowRequested : VoiceAgentCallEvent

    data class StartFinished(
        val operation: VoiceAgentStartOperation,
        val outcome: VoiceAgentStartOutcome,
    ) : VoiceAgentCallEvent

    data class CleanupFinished(
        val cleanup: VoiceAgentCleanupOperation,
        val result: VoiceAgentCleanupResult,
    ) : VoiceAgentCallEvent

    data class SessionStateChanged(
        val call: ActiveVoiceAgentCall,
        val state: VoiceAgentUiState,
        val routeUsable: Boolean,
    ) : VoiceAgentCallEvent
}

internal sealed interface VoiceAgentCallEffect {
    data class AdmitStart(val pending: PendingVoiceAgentStart) : VoiceAgentCallEffect
    data class LaunchStart(val operation: VoiceAgentStartOperation) : VoiceAgentCallEffect
    data class CancelStart(val operation: VoiceAgentStartOperation) : VoiceAgentCallEffect

    data class RunCleanup(
        val cleanup: VoiceAgentCleanupOperation,
        val mode: VoiceAgentCleanupMode,
    ) : VoiceAgentCallEffect

    data class CompleteStarts(
        val replies: List<CompletableDeferred<VoiceAgentCallStartResult>>,
        val result: VoiceAgentCallStartResult,
    ) : VoiceAgentCallEffect

    data class CompleteStartsWithCancellation(
        val replies: List<CompletableDeferred<VoiceAgentCallStartResult>>,
        val error: CancellationException,
    ) : VoiceAgentCallEffect

    data class CompleteEnds(
        val replies: List<CompletableDeferred<VoiceAgentCallEndResult>>,
        val result: VoiceAgentCallEndResult,
    ) : VoiceAgentCallEffect

    data class CompleteCancellations(
        val cancellations: List<PendingVoiceAgentCancellation>,
        val cleanupFailure: Throwable?,
    ) : VoiceAgentCallEffect

    data class Reconnect(val call: ActiveVoiceAgentCall) : VoiceAgentCallEffect

    data class ApplySessionState(
        val call: ActiveVoiceAgentCall,
        val state: VoiceAgentUiState,
    ) : VoiceAgentCallEffect

    data class RecordDiagnostic(
        val call: ActiveVoiceAgentCall,
        val name: String,
        val detail: String,
    ) : VoiceAgentCallEffect

    data class ApplyCallStatus(
        val call: ActiveVoiceAgentCall,
        val status: VoiceCallStatus,
    ) : VoiceAgentCallEffect
}

internal data class VoiceAgentCallTransition(
    val state: VoiceAgentCallState,
    val effects: List<VoiceAgentCallEffect>,
)

internal fun reduceVoiceAgentCallState(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent,
): VoiceAgentCallTransition = when (event) {
    is VoiceAgentCallEvent.StartRequested -> reduceStartRequested(state, event.pending)
    is VoiceAgentCallEvent.StartAdmitted -> reduceStartAdmitted(state, event)
    is VoiceAgentCallEvent.StartCancelled -> reduceStartCancelled(state, event)
    is VoiceAgentCallEvent.EndRequested -> reduceEndRequested(state, event.reply)
    VoiceAgentCallEvent.CloseNowRequested -> reduceCloseNowRequested(state)
    is VoiceAgentCallEvent.StartFinished -> reduceStartFinished(state, event)
    is VoiceAgentCallEvent.CleanupFinished -> reduceCleanupFinished(state, event)
    is VoiceAgentCallEvent.SessionStateChanged -> reduceSessionStateChanged(state, event)
}

private fun reduceStartRequested(
    state: VoiceAgentCallState,
    incoming: PendingVoiceAgentStart,
): VoiceAgentCallTransition = when (state) {
    VoiceAgentCallState.Idle -> transition(
        VoiceAgentCallState.Starting.Admitting(incoming),
        VoiceAgentCallEffect.AdmitStart(incoming),
    )
    is VoiceAgentCallState.Starting.Admitting -> {
        if (state.pending.matches(incoming)) {
            transition(state.copy(pending = state.pending.append(incoming)))
        } else {
            transition(
                VoiceAgentCallState.Starting.Admitting(incoming),
                VoiceAgentCallEffect.CompleteStarts(
                    state.pending.replies,
                    VoiceAgentCallStartResult.Superseded,
                ),
                VoiceAgentCallEffect.AdmitStart(incoming),
            )
        }
    }
    is VoiceAgentCallState.Starting.Running -> {
        if (state.pending.matches(incoming)) {
            transition(state.copy(pending = state.pending.append(incoming)))
        } else {
            transition(
                VoiceAgentCallState.Stopping.ForReplacement(
                    cleanup = state.operation.cleanup,
                    pending = incoming,
                    supersededStarts = state.pending.replies,
                    ends = emptyList(),
                    cancellations = emptyList(),
                ),
                VoiceAgentCallEffect.CancelStart(state.operation),
                VoiceAgentCallEffect.RunCleanup(
                    state.operation.cleanup,
                    VoiceAgentCleanupMode.Replacement,
                ),
            )
        }
    }
    is VoiceAgentCallState.Active -> {
        if (state.call.request == incoming.request) {
            val effects = buildList {
                add(
                    VoiceAgentCallEffect.CompleteStarts(
                        incoming.replies,
                        VoiceAgentCallStartResult.Active(state.call.route),
                    ),
                )
                if (state.sessionState.session is VoiceSessionStatus.Error) {
                    add(VoiceAgentCallEffect.Reconnect(state.call))
                }
            }
            VoiceAgentCallTransition(state, effects)
        } else {
            transition(
                VoiceAgentCallState.Stopping.ForReplacement(
                    cleanup = state.call.cleanup,
                    pending = incoming,
                    supersededStarts = emptyList(),
                    ends = emptyList(),
                    cancellations = emptyList(),
                ),
                VoiceAgentCallEffect.RunCleanup(state.call.cleanup, VoiceAgentCleanupMode.Replacement),
            )
        }
    }
    is VoiceAgentCallState.Stopping.ForEnd -> transition(
        VoiceAgentCallState.Stopping.ForReplacement(
            cleanup = state.cleanup,
            pending = incoming,
            supersededStarts = state.supersededStarts,
            ends = state.ends,
            cancellations = state.cancellations,
        ),
    )
    is VoiceAgentCallState.Stopping.ForReplacement -> {
        if (state.pending.matches(incoming)) {
            transition(state.copy(pending = state.pending.append(incoming)))
        } else {
            transition(
                state.copy(
                    pending = incoming,
                    supersededStarts = state.supersededStarts + state.pending.replies,
                ),
            )
        }
    }
    is VoiceAgentCallState.CleanupFailed -> transition(
        VoiceAgentCallState.Stopping.ForReplacement(
            cleanup = state.cleanup,
            pending = incoming,
            supersededStarts = emptyList(),
            ends = emptyList(),
            cancellations = emptyList(),
        ),
        VoiceAgentCallEffect.RunCleanup(state.cleanup, VoiceAgentCleanupMode.Immediate),
    )
}

private fun reduceStartAdmitted(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent.StartAdmitted,
): VoiceAgentCallTransition {
    if (state is VoiceAgentCallState.Starting.Admitting && state.pending.token === event.pendingToken) {
        return transition(
            VoiceAgentCallState.Starting.Running(state.pending, event.operation),
            VoiceAgentCallEffect.LaunchStart(event.operation),
        )
    }
    return transition(
        state,
        VoiceAgentCallEffect.CancelStart(event.operation),
        VoiceAgentCallEffect.RunCleanup(event.operation.cleanup, VoiceAgentCleanupMode.Immediate),
    )
}

private fun reduceStartCancelled(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent.StartCancelled,
): VoiceAgentCallTransition = when (state) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Active,
    is VoiceAgentCallState.CleanupFailed,
    -> completeLocalCancellation(state, event.cancellation)
    is VoiceAgentCallState.Starting.Admitting -> {
        val remaining = state.pending.replies.withoutIdentity(event.reply)
        when {
            remaining.size == state.pending.replies.size -> completeLocalCancellation(state, event.cancellation)
            remaining.isNotEmpty() -> transition(
                state.copy(pending = state.pending.copy(replies = remaining)),
                VoiceAgentCallEffect.CompleteCancellations(listOf(event.cancellation), null),
            )
            else -> transition(
                VoiceAgentCallState.Idle,
                VoiceAgentCallEffect.CompleteCancellations(listOf(event.cancellation), null),
            )
        }
    }
    is VoiceAgentCallState.Starting.Running -> {
        val remaining = state.pending.replies.withoutIdentity(event.reply)
        when {
            remaining.size == state.pending.replies.size -> completeLocalCancellation(state, event.cancellation)
            remaining.isNotEmpty() -> transition(
                state.copy(pending = state.pending.copy(replies = remaining)),
                VoiceAgentCallEffect.CompleteCancellations(listOf(event.cancellation), null),
            )
            else -> transition(
                VoiceAgentCallState.Stopping.ForEnd(
                    cleanup = state.operation.cleanup,
                    supersededStarts = emptyList(),
                    ends = emptyList(),
                    cancellations = listOf(event.cancellation),
                ),
                VoiceAgentCallEffect.CancelStart(state.operation),
                VoiceAgentCallEffect.RunCleanup(state.operation.cleanup, VoiceAgentCleanupMode.Immediate),
            )
        }
    }
    is VoiceAgentCallState.Stopping.ForEnd -> cancelRetainedWaiter(state, event)
    is VoiceAgentCallState.Stopping.ForReplacement -> cancelReplacementWaiter(state, event)
}

private fun cancelRetainedWaiter(
    state: VoiceAgentCallState.Stopping.ForEnd,
    event: VoiceAgentCallEvent.StartCancelled,
): VoiceAgentCallTransition {
    val remaining = state.supersededStarts.withoutIdentity(event.reply)
    return if (remaining.size == state.supersededStarts.size) {
        completeLocalCancellation(state, event.cancellation)
    } else {
        transition(
            state.copy(
                supersededStarts = remaining,
                cancellations = state.cancellations + event.cancellation,
            ),
        )
    }
}

private fun cancelReplacementWaiter(
    state: VoiceAgentCallState.Stopping.ForReplacement,
    event: VoiceAgentCallEvent.StartCancelled,
): VoiceAgentCallTransition {
    val pendingRemaining = state.pending.replies.withoutIdentity(event.reply)
    if (pendingRemaining.size != state.pending.replies.size) {
        return if (pendingRemaining.isNotEmpty()) {
            transition(
                state.copy(pending = state.pending.copy(replies = pendingRemaining)),
                VoiceAgentCallEffect.CompleteCancellations(listOf(event.cancellation), null),
            )
        } else {
            transition(
                VoiceAgentCallState.Stopping.ForEnd(
                    cleanup = state.cleanup,
                    supersededStarts = state.supersededStarts,
                    ends = state.ends,
                    cancellations = state.cancellations + event.cancellation,
                ),
            )
        }
    }
    val supersededRemaining = state.supersededStarts.withoutIdentity(event.reply)
    return if (supersededRemaining.size == state.supersededStarts.size) {
        completeLocalCancellation(state, event.cancellation)
    } else {
        transition(
            state.copy(
                supersededStarts = supersededRemaining,
                cancellations = state.cancellations + event.cancellation,
            ),
        )
    }
}

private fun reduceEndRequested(
    state: VoiceAgentCallState,
    reply: CompletableDeferred<VoiceAgentCallEndResult>,
): VoiceAgentCallTransition = when (state) {
    VoiceAgentCallState.Idle -> transition(
        state,
        VoiceAgentCallEffect.CompleteEnds(listOf(reply), VoiceAgentCallEndResult.Completed),
    )
    is VoiceAgentCallState.Starting.Admitting -> transition(
        VoiceAgentCallState.Idle,
        VoiceAgentCallEffect.CompleteStarts(
            state.pending.replies,
            VoiceAgentCallStartResult.Superseded,
        ),
        VoiceAgentCallEffect.CompleteEnds(listOf(reply), VoiceAgentCallEndResult.Completed),
    )
    is VoiceAgentCallState.Starting.Running -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.operation.cleanup,
            supersededStarts = state.pending.replies,
            ends = listOf(reply),
            cancellations = emptyList(),
        ),
        VoiceAgentCallEffect.CancelStart(state.operation),
        VoiceAgentCallEffect.RunCleanup(state.operation.cleanup, VoiceAgentCleanupMode.Immediate),
    )
    is VoiceAgentCallState.Active -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.call.cleanup,
            supersededStarts = emptyList(),
            ends = listOf(reply),
            cancellations = emptyList(),
        ),
        VoiceAgentCallEffect.RunCleanup(state.call.cleanup, VoiceAgentCleanupMode.GracefulEnd),
    )
    is VoiceAgentCallState.Stopping.ForEnd -> transition(state.copy(ends = state.ends + reply))
    is VoiceAgentCallState.Stopping.ForReplacement -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.cleanup,
            supersededStarts = state.supersededStarts + state.pending.replies,
            ends = state.ends + reply,
            cancellations = state.cancellations,
        ),
    )
    is VoiceAgentCallState.CleanupFailed -> transition(
        state,
        VoiceAgentCallEffect.CompleteEnds(
            listOf(reply),
            VoiceAgentCallEndResult.Failed(state.error),
        ),
    )
}

private fun reduceCloseNowRequested(state: VoiceAgentCallState): VoiceAgentCallTransition = when (state) {
    VoiceAgentCallState.Idle -> transition(state)
    is VoiceAgentCallState.Starting.Admitting -> transition(
        VoiceAgentCallState.Idle,
        VoiceAgentCallEffect.CompleteStarts(
            state.pending.replies,
            VoiceAgentCallStartResult.Superseded,
        ),
    )
    is VoiceAgentCallState.Starting.Running -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.operation.cleanup,
            supersededStarts = state.pending.replies,
            ends = emptyList(),
            cancellations = emptyList(),
        ),
        VoiceAgentCallEffect.CancelStart(state.operation),
        VoiceAgentCallEffect.RunCleanup(state.operation.cleanup, VoiceAgentCleanupMode.Immediate),
    )
    is VoiceAgentCallState.Active -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.call.cleanup,
            supersededStarts = emptyList(),
            ends = emptyList(),
            cancellations = emptyList(),
        ),
        VoiceAgentCallEffect.RunCleanup(state.call.cleanup, VoiceAgentCleanupMode.Immediate),
    )
    is VoiceAgentCallState.Stopping.ForEnd -> transition(state)
    is VoiceAgentCallState.Stopping.ForReplacement -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.cleanup,
            supersededStarts = state.supersededStarts + state.pending.replies,
            ends = state.ends,
            cancellations = state.cancellations,
        ),
    )
    is VoiceAgentCallState.CleanupFailed -> transition(
        VoiceAgentCallState.Stopping.ForEnd(
            cleanup = state.cleanup,
            supersededStarts = emptyList(),
            ends = emptyList(),
            cancellations = emptyList(),
        ),
        VoiceAgentCallEffect.RunCleanup(state.cleanup, VoiceAgentCleanupMode.Immediate),
    )
}

private fun reduceStartFinished(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent.StartFinished,
): VoiceAgentCallTransition {
    if (state !is VoiceAgentCallState.Starting.Running || state.operation !== event.operation) {
        return reduceStaleStartFinished(state, event.outcome)
    }
    return when (val outcome = event.outcome) {
        is VoiceAgentStartOutcome.Ready -> VoiceAgentCallTransition(
            VoiceAgentCallState.Active(outcome.call, outcome.sessionState),
            activePublicationEffects(state.pending.replies, outcome.call, outcome.sessionState),
        )
        is VoiceAgentStartOutcome.FailedClean -> transition(
            VoiceAgentCallState.Idle,
            VoiceAgentCallEffect.CompleteStarts(
                state.pending.replies,
                VoiceAgentCallStartResult.Failed(outcome.error),
            ),
        )
        is VoiceAgentStartOutcome.FailedDirty -> transition(
            VoiceAgentCallState.CleanupFailed(outcome.cleanup, outcome.error),
            VoiceAgentCallEffect.CompleteStarts(
                state.pending.replies,
                VoiceAgentCallStartResult.Failed(outcome.error),
            ),
        )
        is VoiceAgentStartOutcome.CancelledClean -> transition(
            VoiceAgentCallState.Idle,
            VoiceAgentCallEffect.CompleteStartsWithCancellation(state.pending.replies, outcome.error),
        )
        is VoiceAgentStartOutcome.CancelledDirty -> transition(
            VoiceAgentCallState.CleanupFailed(outcome.cleanup, outcome.error),
            VoiceAgentCallEffect.CompleteStartsWithCancellation(state.pending.replies, outcome.error),
        )
        VoiceAgentStartOutcome.Cancelled -> transition(
            VoiceAgentCallState.Idle,
            VoiceAgentCallEffect.CompleteStarts(
                state.pending.replies,
                VoiceAgentCallStartResult.Superseded,
            ),
        )
    }
}

private fun activePublicationEffects(
    replies: List<CompletableDeferred<VoiceAgentCallStartResult>>,
    call: ActiveVoiceAgentCall,
    sessionState: VoiceAgentUiState,
): List<VoiceAgentCallEffect> = buildList {
    add(VoiceAgentCallEffect.CompleteStarts(replies, VoiceAgentCallStartResult.Active(call.route)))
    val routeFailure = call.route.failure
    if (routeFailure != null) {
        add(VoiceAgentCallEffect.RecordDiagnostic(call, routeFailure.diagnosticName, routeFailure.detail))
        add(VoiceAgentCallEffect.ApplyCallStatus(call, VoiceCallStatus.Degraded(routeFailure.detail)))
    } else if (call.route.owner == VoiceAudioRouteOwner.Telecom) {
        add(VoiceAgentCallEffect.ApplyCallStatus(call, VoiceCallStatus.BackgroundCapable))
    }
    val sessionError = sessionState.session as? VoiceSessionStatus.Error
    if (sessionError != null && call.session.isRouteUsable) {
        add(VoiceAgentCallEffect.RecordDiagnostic(call, "voice_call_start_failed", sessionError.message))
    }
}

private fun reduceStaleStartFinished(
    state: VoiceAgentCallState,
    outcome: VoiceAgentStartOutcome,
): VoiceAgentCallTransition = when (outcome) {
    is VoiceAgentStartOutcome.Ready -> transition(
        state,
        VoiceAgentCallEffect.RunCleanup(outcome.call.cleanup, VoiceAgentCleanupMode.Immediate),
    )
    is VoiceAgentStartOutcome.FailedDirty -> transition(
        state,
        VoiceAgentCallEffect.RunCleanup(outcome.cleanup, VoiceAgentCleanupMode.Immediate),
    )
    is VoiceAgentStartOutcome.CancelledDirty -> transition(
        state,
        VoiceAgentCallEffect.RunCleanup(outcome.cleanup, VoiceAgentCleanupMode.Immediate),
    )
    is VoiceAgentStartOutcome.FailedClean,
    is VoiceAgentStartOutcome.CancelledClean,
    VoiceAgentStartOutcome.Cancelled,
    -> transition(state)
}

private fun reduceCleanupFinished(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent.CleanupFinished,
): VoiceAgentCallTransition {
    if (state !is VoiceAgentCallState.Stopping || state.cleanup !== event.cleanup) {
        return transition(state)
    }
    return when (val result = event.result) {
        VoiceAgentCleanupResult.Completed -> cleanupCompleted(state)
        is VoiceAgentCleanupResult.Failed -> cleanupFailed(state, result.error)
    }
}

private fun cleanupCompleted(state: VoiceAgentCallState.Stopping): VoiceAgentCallTransition {
    val effects = terminalWaiterEffects(
        supersededStarts = state.supersededStarts,
        ends = state.ends,
        endResult = VoiceAgentCallEndResult.Completed,
    ).toMutableList()
    if (state.cancellations.isNotEmpty()) {
        effects += VoiceAgentCallEffect.CompleteCancellations(state.cancellations, null)
    }
    val next = when (state) {
        is VoiceAgentCallState.Stopping.ForEnd -> VoiceAgentCallState.Idle
        is VoiceAgentCallState.Stopping.ForReplacement -> {
            effects += VoiceAgentCallEffect.AdmitStart(state.pending)
            VoiceAgentCallState.Starting.Admitting(state.pending)
        }
    }
    return VoiceAgentCallTransition(next, effects)
}

private fun cleanupFailed(
    state: VoiceAgentCallState.Stopping,
    error: Throwable,
): VoiceAgentCallTransition {
    val effects = terminalWaiterEffects(
        supersededStarts = state.supersededStarts,
        ends = state.ends,
        endResult = VoiceAgentCallEndResult.Failed(error),
    ).toMutableList()
    if (state is VoiceAgentCallState.Stopping.ForReplacement) {
        effects += VoiceAgentCallEffect.CompleteStarts(
            state.pending.replies,
            VoiceAgentCallStartResult.Failed(error),
        )
    }
    if (state.cancellations.isNotEmpty()) {
        effects += VoiceAgentCallEffect.CompleteCancellations(state.cancellations, error)
    }
    return VoiceAgentCallTransition(
        VoiceAgentCallState.CleanupFailed(state.cleanup, error),
        effects,
    )
}

private fun terminalWaiterEffects(
    supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>,
    ends: List<CompletableDeferred<VoiceAgentCallEndResult>>,
    endResult: VoiceAgentCallEndResult,
): List<VoiceAgentCallEffect> = buildList {
    if (supersededStarts.isNotEmpty()) {
        add(
            VoiceAgentCallEffect.CompleteStarts(
                supersededStarts,
                VoiceAgentCallStartResult.Superseded,
            ),
        )
    }
    if (ends.isNotEmpty()) {
        add(VoiceAgentCallEffect.CompleteEnds(ends, endResult))
    }
}

private fun reduceSessionStateChanged(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent.SessionStateChanged,
): VoiceAgentCallTransition {
    if (state !is VoiceAgentCallState.Active || state.call.token !== event.call.token) {
        return transition(state)
    }
    return when (val status = event.state.session) {
        is VoiceSessionStatus.Error -> {
            if (event.routeUsable) {
                val effects = buildList {
                    if (state.sessionState.session != status) {
                        add(
                            VoiceAgentCallEffect.RecordDiagnostic(
                                state.call,
                                "voice_call_start_failed",
                                status.message,
                            ),
                        )
                    }
                    add(
                        VoiceAgentCallEffect.ApplySessionState(
                            state.call,
                            event.state.copy(call = VoiceCallStatus.Degraded(status.message)),
                        ),
                    )
                }
                VoiceAgentCallTransition(state.copy(sessionState = event.state), effects)
            } else {
                detachForImmediateCleanup(state.call)
            }
        }
        VoiceSessionStatus.Ended -> detachForImmediateCleanup(state.call)
        else -> transition(
            state.copy(sessionState = event.state),
            VoiceAgentCallEffect.ApplySessionState(state.call, event.state),
        )
    }
}

private fun detachForImmediateCleanup(call: ActiveVoiceAgentCall): VoiceAgentCallTransition = transition(
    VoiceAgentCallState.Stopping.ForEnd(
        cleanup = call.cleanup,
        supersededStarts = emptyList(),
        ends = emptyList(),
        cancellations = emptyList(),
    ),
    VoiceAgentCallEffect.RunCleanup(call.cleanup, VoiceAgentCleanupMode.Immediate),
)

private fun PendingVoiceAgentStart.matches(other: PendingVoiceAgentStart): Boolean = request == other.request

private fun PendingVoiceAgentStart.append(other: PendingVoiceAgentStart): PendingVoiceAgentStart =
    copy(replies = replies + other.replies)

private fun <T> List<T>.withoutIdentity(target: T): List<T> = filterNot { it === target }

private fun completeLocalCancellation(
    state: VoiceAgentCallState,
    cancellation: PendingVoiceAgentCancellation,
): VoiceAgentCallTransition = transition(
    state,
    VoiceAgentCallEffect.CompleteCancellations(listOf(cancellation), null),
)

private fun transition(
    state: VoiceAgentCallState,
    vararg effects: VoiceAgentCallEffect,
): VoiceAgentCallTransition = VoiceAgentCallTransition(state, effects.toList())
