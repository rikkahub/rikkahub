package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import me.rerere.rikkahub.decodeVoiceAgentTransport
import me.rerere.rikkahub.voiceAgentIntentScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.uuid.Uuid

class VoiceAgentCallStateMachineTest {
    @Test
    fun `LiveKit notification round trip completes active start without replacement cleanup`() {
        val activeRequest = request("notification").copy(
            transport = VoiceAgentTransport.LiveKitExperimental,
        )
        val routeFields = encodeVoiceAgentNotificationRouteFields(
            conversationId = activeRequest.conversationId.toString(),
            transport = activeRequest.transport,
        )
        val screen = requireNotNull(
            voiceAgentIntentScreen(
                conversationId = routeFields.conversationId,
                transportWireName = routeFields.transportWireName,
            ),
        )
        val reopenedRequest = activeRequest.copy(
            transport = decodeVoiceAgentTransport(screen.transportWireName),
        )
        val state = activeState(activeCall(activeRequest))

        val transition = reduceVoiceAgentCallState(
            state,
            VoiceAgentCallEvent.StartRequested(pending(reopenedRequest)),
        )

        assertSame(state, transition.state)
        assertType<VoiceAgentCallEffect.CompleteStarts>(transition.effects.single())
        assertFalse(transition.effects.any { it is VoiceAgentCallEffect.RunCleanup })
    }

    @Test
    fun `idle handles every legal event without acquiring hidden ownership`() {
        val request = request("idle")
        val pending = pending(request)
        val operation = FakeStartOperation(request)
        val staleCleanup = FakeCleanupOperation()
        val staleCall = activeCall(request)
        val endReply = endReply()
        val cancellation = pendingCancellation()
        val cancelledReply = startReply()
        val cleanFailure = Exception("stale")

        val cases = listOf(
            IdleCase(
                event = VoiceAgentCallEvent.StartRequested(pending),
                expectedState = VoiceAgentCallState.Starting.Admitting::class.java,
                expectedEffects = listOf(VoiceAgentCallEffect.AdmitStart(pending)),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.EndRequested(endReply),
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = listOf(
                    VoiceAgentCallEffect.CompleteEnds(
                        listOf(endReply),
                        VoiceAgentCallEndResult.Completed,
                    ),
                ),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.CloseNowRequested,
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = emptyList(),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.StartCancelled(cancelledReply, cancellation),
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = listOf(
                    VoiceAgentCallEffect.CompleteCancellations(listOf(cancellation), null),
                ),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.StartAdmitted(Any(), operation),
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = listOf(
                    VoiceAgentCallEffect.CancelStart(operation),
                    VoiceAgentCallEffect.RunCleanup(operation.cleanup, VoiceAgentCleanupMode.Immediate),
                ),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.StartFinished(
                    operation,
                    VoiceAgentStartOutcome.FailedClean(cleanFailure),
                ),
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = emptyList(),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.CleanupFinished(staleCleanup, VoiceAgentCleanupResult.Completed),
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = emptyList(),
            ),
            IdleCase(
                event = VoiceAgentCallEvent.SessionStateChanged(staleCall, VoiceAgentUiState(), true),
                expectedState = VoiceAgentCallState.Idle::class.java,
                expectedEffects = emptyList(),
            ),
        )

        cases.forEach { case ->
            val transition = reduceVoiceAgentCallState(VoiceAgentCallState.Idle, case.event)
            assertEquals(case.expectedState, transition.state.javaClass)
            assertEquals(case.expectedEffects, transition.effects)
        }
    }

    @Test
    fun `matching active reduction is unchanged by mutable session flow`() {
        val request = request("deterministic")
        val call = activeCall(request, sessionStatus = VoiceSessionStatus.Connected)
        val state = activeState(call)
        val event = VoiceAgentCallEvent.StartRequested(pending(request))

        val beforeMutation = reduceVoiceAgentCallState(state, event)
        call.testSession.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Error("out of band"))
        val afterMutation = reduceVoiceAgentCallState(state, event)

        assertEquals(beforeMutation, afterMutation)
        assertEquals(1, afterMutation.effects.size)
        assertType<VoiceAgentCallEffect.CompleteStarts>(afterMutation.effects.single())
    }

    @Test
    fun `start and active transitions preserve ownership and effect order`() {
        val first = request("first")
        val second = request("second")
        val firstPending = pending(first)
        val secondPending = pending(second)
        val operation = FakeStartOperation(first)
        val active = activeCall(first, sessionStatus = VoiceSessionStatus.Connected)

        val cases = listOf(
            TransitionCase("idle admission") {
                val transition = reduceVoiceAgentCallState(
                    VoiceAgentCallState.Idle,
                    VoiceAgentCallEvent.StartRequested(firstPending),
                )
                assertSame(firstPending, assertType<VoiceAgentCallState.Starting.Admitting>(transition.state).pending)
                assertEquals(listOf(VoiceAgentCallEffect.AdmitStart(firstPending)), transition.effects)
            },
            TransitionCase("start admitted") {
                val admitting = VoiceAgentCallState.Starting.Admitting(firstPending)
                val transition = reduceVoiceAgentCallState(
                    admitting,
                    VoiceAgentCallEvent.StartAdmitted(firstPending.token, operation),
                )
                val running = assertType<VoiceAgentCallState.Starting.Running>(transition.state)
                assertSame(firstPending, running.pending)
                assertSame(operation, running.operation)
                assertEquals(listOf(VoiceAgentCallEffect.LaunchStart(operation)), transition.effects)
            },
            TransitionCase("matching starting appends waiter") {
                val running = VoiceAgentCallState.Starting.Running(firstPending, operation)
                val matching = pending(first)
                val transition = reduceVoiceAgentCallState(running, VoiceAgentCallEvent.StartRequested(matching))
                val next = assertType<VoiceAgentCallState.Starting.Running>(transition.state)
                assertSame(operation, next.operation)
                assertEquals(2, next.pending.replies.size)
                assertTrue(transition.effects.isEmpty())
            },
            TransitionCase("different running startup cancels before replacement cleanup") {
                val running = VoiceAgentCallState.Starting.Running(firstPending, operation)
                val transition = reduceVoiceAgentCallState(running, VoiceAgentCallEvent.StartRequested(secondPending))
                val next = assertType<VoiceAgentCallState.Stopping.ForReplacement>(transition.state)
                assertSame(operation.cleanup, next.cleanup)
                assertSame(secondPending, next.pending)
                assertEquals(firstPending.replies, next.supersededStarts)
                assertEquals(
                    listOf(
                        VoiceAgentCallEffect.CancelStart(operation),
                        VoiceAgentCallEffect.RunCleanup(operation.cleanup, VoiceAgentCleanupMode.Replacement),
                    ),
                    transition.effects,
                )
            },
            TransitionCase("matching active completes without reconnect") {
                val state = activeState(active)
                val transition = reduceVoiceAgentCallState(state, VoiceAgentCallEvent.StartRequested(firstPending))
                assertSame(state, transition.state)
                assertEquals(1, transition.effects.size)
                val completion = assertType<VoiceAgentCallEffect.CompleteStarts>(transition.effects.single())
                assertEquals(VoiceAgentCallStartResult.Active(active.route), completion.result)
            },
            TransitionCase("matching active error reconnects after completion") {
                val errorActive = activeCall(first, sessionStatus = VoiceSessionStatus.Error("retry"))
                val state = activeState(
                    errorActive,
                    VoiceAgentUiState(session = VoiceSessionStatus.Error("retry")),
                )
                val transition = reduceVoiceAgentCallState(state, VoiceAgentCallEvent.StartRequested(firstPending))
                assertSame(state, transition.state)
                assertType<VoiceAgentCallEffect.CompleteStarts>(transition.effects[0])
                assertEquals(VoiceAgentCallEffect.Reconnect(errorActive), transition.effects[1])
            },
            TransitionCase("different active starts replacement cleanup") {
                val transition = reduceVoiceAgentCallState(
                    activeState(active),
                    VoiceAgentCallEvent.StartRequested(secondPending),
                )
                val next = assertType<VoiceAgentCallState.Stopping.ForReplacement>(transition.state)
                assertSame(active.cleanup, next.cleanup)
                assertSame(secondPending, next.pending)
                assertEquals(
                    listOf(VoiceAgentCallEffect.RunCleanup(active.cleanup, VoiceAgentCleanupMode.Replacement)),
                    transition.effects,
                )
            },
        )

        cases.forEach { case ->
            try {
                case.verify()
            } catch (error: AssertionError) {
                throw AssertionError("Transition case failed: ${case.name}", error)
            }
        }
    }

    @Test
    fun `stopping keeps one cleanup while matching appends and different replaces pending`() {
        val cleanup = FakeCleanupOperation()
        val old = pending(request("old"))
        val state = VoiceAgentCallState.Stopping.ForReplacement(
            cleanup = cleanup,
            pending = old,
            supersededStarts = emptyList(),
            ends = emptyList(),
            cancellations = emptyList(),
        )

        val matching = pending(old.request)
        val matched = reduceVoiceAgentCallState(state, VoiceAgentCallEvent.StartRequested(matching))
        val matchedState = assertType<VoiceAgentCallState.Stopping.ForReplacement>(matched.state)
        assertSame(cleanup, matchedState.cleanup)
        assertEquals(2, matchedState.pending.replies.size)
        assertTrue(matched.effects.isEmpty())

        val newer = pending(request("newer"))
        val replaced = reduceVoiceAgentCallState(matchedState, VoiceAgentCallEvent.StartRequested(newer))
        val replacedState = assertType<VoiceAgentCallState.Stopping.ForReplacement>(replaced.state)
        assertSame(cleanup, replacedState.cleanup)
        assertSame(newer, replacedState.pending)
        assertEquals(matchedState.pending.replies, replacedState.supersededStarts)
        assertTrue(replaced.effects.isEmpty())
        matchedState.pending.replies.forEach { assertFalse(it.isCompleted) }
    }

    @Test
    fun `start during for-end reuses cleanup and end during replacement removes pending`() {
        val cleanup = FakeCleanupOperation()
        val endReply = endReply()
        val forEnd = VoiceAgentCallState.Stopping.ForEnd(
            cleanup = cleanup,
            supersededStarts = emptyList(),
            ends = listOf(endReply),
            cancellations = emptyList(),
        )
        val pending = pending(request("replacement"))

        val replaced = reduceVoiceAgentCallState(forEnd, VoiceAgentCallEvent.StartRequested(pending))
        val replacement = assertType<VoiceAgentCallState.Stopping.ForReplacement>(replaced.state)
        assertSame(cleanup, replacement.cleanup)
        assertSame(pending, replacement.pending)
        assertEquals(listOf(endReply), replacement.ends)
        assertTrue(replaced.effects.isEmpty())

        val laterEnd = endReply()
        val ended = reduceVoiceAgentCallState(replacement, VoiceAgentCallEvent.EndRequested(laterEnd))
        val next = assertType<VoiceAgentCallState.Stopping.ForEnd>(ended.state)
        assertSame(cleanup, next.cleanup)
        assertEquals(pending.replies, next.supersededStarts)
        assertEquals(listOf(endReply, laterEnd), next.ends)
        assertTrue(ended.effects.isEmpty())
        pending.replies.forEach { assertFalse(it.isCompleted) }
        assertFalse(endReply.isCompleted)
        assertFalse(laterEnd.isCompleted)
    }

    @Test
    fun `end chooses immediate startup cleanup and graceful active cleanup`() {
        val request = request("call")
        val operation = FakeStartOperation(request)
        val pending = pending(request)
        val startEnd = endReply()
        val starting = VoiceAgentCallState.Starting.Running(pending, operation)

        val stoppedStart = reduceVoiceAgentCallState(starting, VoiceAgentCallEvent.EndRequested(startEnd))
        val startState = assertType<VoiceAgentCallState.Stopping.ForEnd>(stoppedStart.state)
        assertSame(operation.cleanup, startState.cleanup)
        assertEquals(pending.replies, startState.supersededStarts)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CancelStart(operation),
                VoiceAgentCallEffect.RunCleanup(operation.cleanup, VoiceAgentCleanupMode.Immediate),
            ),
            stoppedStart.effects,
        )

        val active = activeCall(request)
        val activeEnd = endReply()
        val stoppedActive = reduceVoiceAgentCallState(
            activeState(active),
            VoiceAgentCallEvent.EndRequested(activeEnd),
        )
        val activeState = assertType<VoiceAgentCallState.Stopping.ForEnd>(stoppedActive.state)
        assertSame(active.cleanup, activeState.cleanup)
        assertEquals(
            listOf(VoiceAgentCallEffect.RunCleanup(active.cleanup, VoiceAgentCleanupMode.GracefulEnd)),
            stoppedActive.effects,
        )
    }

    @Test
    fun `canceling one matching waiter preserves startup and completes only that cancellation`() {
        val request = request("shared")
        val firstReply = startReply()
        val secondReply = startReply()
        val pending = PendingVoiceAgentStart(Any(), request, listOf(firstReply, secondReply))
        val operation = FakeStartOperation(request)
        val state = VoiceAgentCallState.Starting.Running(pending, operation)
        val cancellation = pendingCancellation()

        val transition = reduceVoiceAgentCallState(
            state,
            VoiceAgentCallEvent.StartCancelled(firstReply, cancellation),
        )

        val next = assertType<VoiceAgentCallState.Starting.Running>(transition.state)
        assertSame(operation, next.operation)
        assertEquals(listOf(secondReply), next.pending.replies)
        assertEquals(
            listOf(VoiceAgentCallEffect.CompleteCancellations(listOf(cancellation), null)),
            transition.effects,
        )
        assertFalse(cancellation.completion.isCompleted)
    }

    @Test
    fun `canceling final startup waiter retains canonical cancellation until exact cleanup`() {
        val request = request("only")
        val reply = startReply()
        val pending = PendingVoiceAgentStart(Any(), request, listOf(reply))
        val operation = FakeStartOperation(request)
        val cancellation = pendingCancellation()

        val cancelled = reduceVoiceAgentCallState(
            VoiceAgentCallState.Starting.Running(pending, operation),
            VoiceAgentCallEvent.StartCancelled(reply, cancellation),
        )

        val stopping = assertType<VoiceAgentCallState.Stopping.ForEnd>(cancelled.state)
        assertSame(operation.cleanup, stopping.cleanup)
        assertEquals(listOf(cancellation), stopping.cancellations)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CancelStart(operation),
                VoiceAgentCallEffect.RunCleanup(operation.cleanup, VoiceAgentCleanupMode.Immediate),
            ),
            cancelled.effects,
        )
        assertFalse(cancellation.completion.isCompleted)

        val cleaned = reduceVoiceAgentCallState(
            stopping,
            VoiceAgentCallEvent.CleanupFinished(operation.cleanup, VoiceAgentCleanupResult.Completed),
        )
        assertType<VoiceAgentCallState.Idle>(cleaned.state)
        assertEquals(
            listOf(VoiceAgentCallEffect.CompleteCancellations(listOf(cancellation), null)),
            cleaned.effects,
        )
    }

    @Test
    fun `canceling final pending replacement keeps cleanup and removes desired start`() {
        val cleanup = FakeCleanupOperation()
        val reply = startReply()
        val pending = PendingVoiceAgentStart(Any(), request("replacement"), listOf(reply))
        val cancellation = pendingCancellation()
        val state = VoiceAgentCallState.Stopping.ForReplacement(
            cleanup = cleanup,
            pending = pending,
            supersededStarts = emptyList(),
            ends = emptyList(),
            cancellations = emptyList(),
        )

        val transition = reduceVoiceAgentCallState(
            state,
            VoiceAgentCallEvent.StartCancelled(reply, cancellation),
        )

        val next = assertType<VoiceAgentCallState.Stopping.ForEnd>(transition.state)
        assertSame(cleanup, next.cleanup)
        assertEquals(listOf(cancellation), next.cancellations)
        assertTrue(transition.effects.isEmpty())
        assertFalse(cancellation.completion.isCompleted)
    }

    @Test
    fun `cleanup success completes old waiters before admitting only newest pending`() {
        val cleanup = FakeCleanupOperation()
        val oldReply = startReply()
        val endReply = endReply()
        val cancellation = pendingCancellation()
        val newest = pending(request("newest"))
        val state = VoiceAgentCallState.Stopping.ForReplacement(
            cleanup = cleanup,
            pending = newest,
            supersededStarts = listOf(oldReply),
            ends = listOf(endReply),
            cancellations = listOf(cancellation),
        )

        val transition = reduceVoiceAgentCallState(
            state,
            VoiceAgentCallEvent.CleanupFinished(cleanup, VoiceAgentCleanupResult.Completed),
        )

        val admitting = assertType<VoiceAgentCallState.Starting.Admitting>(transition.state)
        assertSame(newest, admitting.pending)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CompleteStarts(
                    listOf(oldReply),
                    VoiceAgentCallStartResult.Superseded,
                ),
                VoiceAgentCallEffect.CompleteEnds(
                    listOf(endReply),
                    VoiceAgentCallEndResult.Completed,
                ),
                VoiceAgentCallEffect.CompleteCancellations(listOf(cancellation), null),
                VoiceAgentCallEffect.AdmitStart(newest),
            ),
            transition.effects,
        )
        assertFalse(oldReply.isCompleted)
        assertFalse(endReply.isCompleted)
        assertFalse(cancellation.completion.isCompleted)
    }

    @Test
    fun `cleanup failure fails retained waiters and pending then publishes retry owner`() {
        val cleanup = FakeCleanupOperation()
        val failure = IllegalStateException("cleanup")
        val oldReply = startReply()
        val endReply = endReply()
        val cancellation = pendingCancellation()
        val pending = pending(request("pending"))
        val state = VoiceAgentCallState.Stopping.ForReplacement(
            cleanup = cleanup,
            pending = pending,
            supersededStarts = listOf(oldReply),
            ends = listOf(endReply),
            cancellations = listOf(cancellation),
        )

        val transition = reduceVoiceAgentCallState(
            state,
            VoiceAgentCallEvent.CleanupFinished(cleanup, VoiceAgentCleanupResult.Failed(failure)),
        )

        val failed = assertType<VoiceAgentCallState.CleanupFailed>(transition.state)
        assertSame(cleanup, failed.cleanup)
        assertSame(failure, failed.error)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CompleteStarts(listOf(oldReply), VoiceAgentCallStartResult.Superseded),
                VoiceAgentCallEffect.CompleteEnds(listOf(endReply), VoiceAgentCallEndResult.Failed(failure)),
                VoiceAgentCallEffect.CompleteStarts(
                    pending.replies,
                    VoiceAgentCallStartResult.Failed(failure),
                ),
                VoiceAgentCallEffect.CompleteCancellations(listOf(cancellation), failure),
            ),
            transition.effects,
        )
    }

    @Test
    fun `cleanup failed retries exact owner only on start or close-now`() {
        val cleanup = FakeCleanupOperation()
        val currentError = IllegalStateException("first")
        val failed = VoiceAgentCallState.CleanupFailed(cleanup, currentError)
        val pending = pending(request("retry"))

        val started = reduceVoiceAgentCallState(failed, VoiceAgentCallEvent.StartRequested(pending))
        val replacement = assertType<VoiceAgentCallState.Stopping.ForReplacement>(started.state)
        assertSame(cleanup, replacement.cleanup)
        assertEquals(
            listOf(VoiceAgentCallEffect.RunCleanup(cleanup, VoiceAgentCleanupMode.Immediate)),
            started.effects,
        )

        val retryError = IllegalArgumentException("again")
        val retried = reduceVoiceAgentCallState(
            replacement,
            VoiceAgentCallEvent.CleanupFinished(cleanup, VoiceAgentCleanupResult.Failed(retryError)),
        )
        val retryFailed = assertType<VoiceAgentCallState.CleanupFailed>(retried.state)
        assertSame(cleanup, retryFailed.cleanup)
        assertSame(retryError, retryFailed.error)

        val endReply = endReply()
        val ended = reduceVoiceAgentCallState(retryFailed, VoiceAgentCallEvent.EndRequested(endReply))
        assertSame(retryFailed, ended.state)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CompleteEnds(
                    listOf(endReply),
                    VoiceAgentCallEndResult.Failed(retryError),
                ),
            ),
            ended.effects,
        )

        val closed = reduceVoiceAgentCallState(retryFailed, VoiceAgentCallEvent.CloseNowRequested)
        val forEnd = assertType<VoiceAgentCallState.Stopping.ForEnd>(closed.state)
        assertSame(cleanup, forEnd.cleanup)
        assertEquals(
            listOf(VoiceAgentCallEffect.RunCleanup(cleanup, VoiceAgentCleanupMode.Immediate)),
            closed.effects,
        )
    }

    @Test
    fun `start completion transfers active or dirty cleanup ownership exactly once`() {
        val request = request("ready")
        val pending = pending(request)
        val operation = FakeStartOperation(request)
        val running = VoiceAgentCallState.Starting.Running(pending, operation)
        val call = activeCall(request)

        val ready = reduceVoiceAgentCallState(
            running,
            VoiceAgentCallEvent.StartFinished(
                operation,
                VoiceAgentStartOutcome.Ready(
                    call,
                    VoiceAgentUiState(session = VoiceSessionStatus.Connected),
                ),
            ),
        )
        val activeState = assertType<VoiceAgentCallState.Active>(ready.state)
        assertSame(call, activeState.call)
        assertEquals(VoiceSessionStatus.Connected, activeState.sessionState.session)
        assertType<VoiceAgentCallEffect.CompleteStarts>(ready.effects.first())

        val cleanError = IllegalArgumentException("clean")
        val clean = reduceVoiceAgentCallState(
            running,
            VoiceAgentCallEvent.StartFinished(operation, VoiceAgentStartOutcome.FailedClean(cleanError)),
        )
        assertType<VoiceAgentCallState.Idle>(clean.state)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CompleteStarts(
                    pending.replies,
                    VoiceAgentCallStartResult.Failed(cleanError),
                ),
            ),
            clean.effects,
        )

        val cancelled = reduceVoiceAgentCallState(
            running,
            VoiceAgentCallEvent.StartFinished(operation, VoiceAgentStartOutcome.Cancelled),
        )
        assertType<VoiceAgentCallState.Idle>(cancelled.state)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CompleteStarts(
                    pending.replies,
                    VoiceAgentCallStartResult.Superseded,
                ),
            ),
            cancelled.effects,
        )

        val dirtyCleanup = FakeCleanupOperation()
        val dirtyError = IllegalStateException("dirty")
        val dirty = reduceVoiceAgentCallState(
            running,
            VoiceAgentCallEvent.StartFinished(
                operation,
                VoiceAgentStartOutcome.FailedDirty(dirtyError, dirtyCleanup),
            ),
        )
        val failed = assertType<VoiceAgentCallState.CleanupFailed>(dirty.state)
        assertSame(dirtyCleanup, failed.cleanup)
        assertSame(dirtyError, failed.error)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CompleteStarts(
                    pending.replies,
                    VoiceAgentCallStartResult.Failed(dirtyError),
                ),
            ),
            dirty.effects,
        )
    }

    @Test
    fun `opaque startup phases do not change reducer admission or completion policy`() {
        val request = request("phases")
        val pending = pending(request)
        val phaseFactories = listOf<(
            VoiceAgentCallRequest,
            CoroutineScope,
            Job,
        ) -> VoiceAgentStartPhase>(
            { value, _, _ -> VoiceAgentStartPhase.Admitted(value) },
            { value, scope, job -> VoiceAgentStartPhase.PreparingRoute(value, scope, job) },
            { value, scope, job -> VoiceAgentStartPhase.CreatingSession(value, scope, job) },
            { value, scope, job ->
                VoiceAgentStartPhase.StartingSession(
                    value,
                    scope,
                    job,
                    InertRouteOwnedSession(VoiceSessionStatus.Connected, FakeCleanupOperation()),
                )
            },
        )

        phaseFactories.forEach { phaseFactory ->
            val operation = FakeStartOperation(request, phaseFactory)
            val admitted = reduceVoiceAgentCallState(
                VoiceAgentCallState.Starting.Admitting(pending),
                VoiceAgentCallEvent.StartAdmitted(pending.token, operation),
            )
            val running = assertType<VoiceAgentCallState.Starting.Running>(admitted.state)
            assertSame(operation, running.operation)
            if (operation.phase is VoiceAgentStartPhase.Admitted) {
                assertSame(operation.phase, running.operation.phase)
            } else {
                assertSame(operation.phase.callJob, running.operation.phase.callJob)
            }

            val failed = reduceVoiceAgentCallState(
                running,
                VoiceAgentCallEvent.StartFinished(
                    operation,
                    VoiceAgentStartOutcome.FailedClean(IllegalStateException("phase")),
                ),
            )
            assertType<VoiceAgentCallState.Idle>(failed.state)
        }
    }

    @Test
    fun `stale identities preserve current state and clean only locally owned resources`() {
        val request = request("current")
        val currentOperation = FakeStartOperation(request)
        val staleOperation = FakeStartOperation(request)
        val current = VoiceAgentCallState.Starting.Running(pending(request), currentOperation)

        val admitted = reduceVoiceAgentCallState(
            current,
            VoiceAgentCallEvent.StartAdmitted(Any(), staleOperation),
        )
        assertSame(current, admitted.state)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.CancelStart(staleOperation),
                VoiceAgentCallEffect.RunCleanup(staleOperation.cleanup, VoiceAgentCleanupMode.Immediate),
            ),
            admitted.effects,
        )

        val staleCall = activeCall(request("stale"))
        val finished = reduceVoiceAgentCallState(
            current,
            VoiceAgentCallEvent.StartFinished(
                staleOperation,
                VoiceAgentStartOutcome.Ready(staleCall, VoiceAgentUiState()),
            ),
        )
        assertSame(current, finished.state)
        assertEquals(
            listOf(VoiceAgentCallEffect.RunCleanup(staleCall.cleanup, VoiceAgentCleanupMode.Immediate)),
            finished.effects,
        )

        val staleCleanup = FakeCleanupOperation()
        val cleanupFinished = reduceVoiceAgentCallState(
            current,
            VoiceAgentCallEvent.CleanupFinished(staleCleanup, VoiceAgentCleanupResult.Completed),
        )
        assertSame(current, cleanupFinished.state)
        assertTrue(cleanupFinished.effects.isEmpty())

        val collector = reduceVoiceAgentCallState(
            activeState(activeCall(request)),
            VoiceAgentCallEvent.SessionStateChanged(staleCall, VoiceAgentUiState(), routeUsable = true),
        )
        assertType<VoiceAgentCallState.Active>(collector.state)
        assertTrue(collector.effects.isEmpty())
    }

    @Test
    fun `session state policy keeps usable errors active and detaches terminal states`() {
        val call = activeCall(request("session"))
        val active = activeState(call)
        val errorState = VoiceAgentUiState(session = VoiceSessionStatus.Error("network"))

        val usableError = reduceVoiceAgentCallState(
            active,
            VoiceAgentCallEvent.SessionStateChanged(call, errorState, routeUsable = true),
        )
        val retained = assertType<VoiceAgentCallState.Active>(usableError.state)
        assertSame(call, retained.call)
        assertEquals(errorState, retained.sessionState)
        assertEquals(
            listOf(
                VoiceAgentCallEffect.RecordDiagnostic(call, "voice_call_start_failed", "network"),
                VoiceAgentCallEffect.ApplySessionState(
                    call,
                    errorState.copy(call = VoiceCallStatus.Degraded("network")),
                ),
            ),
            usableError.effects,
        )

        listOf(
            VoiceAgentCallEvent.SessionStateChanged(call, errorState, routeUsable = false),
            VoiceAgentCallEvent.SessionStateChanged(
                call,
                VoiceAgentUiState(session = VoiceSessionStatus.Ended),
                routeUsable = true,
            ),
        ).forEach { event ->
            val transition = reduceVoiceAgentCallState(active, event)
            val stopping = assertType<VoiceAgentCallState.Stopping.ForEnd>(transition.state)
            assertSame(call.cleanup, stopping.cleanup)
            assertEquals(
                VoiceAgentCallEffect.RunCleanup(call.cleanup, VoiceAgentCleanupMode.Immediate),
                transition.effects.last(),
            )
        }
    }

    private data class TransitionCase(
        val name: String,
        val verify: () -> Unit,
    )

    private data class IdleCase(
        val event: VoiceAgentCallEvent,
        val expectedState: Class<out VoiceAgentCallState>,
        val expectedEffects: List<VoiceAgentCallEffect>,
    )
}

internal class FakeStartOperation(
    override val request: VoiceAgentCallRequest,
    phaseFactory: (
        VoiceAgentCallRequest,
        CoroutineScope,
        Job,
    ) -> VoiceAgentStartPhase = { value, scope, job ->
        VoiceAgentStartPhase.PreparingRoute(value, scope, job)
    },
) : VoiceAgentStartOperation {
    override val token: Any = Any()
    private val phaseJob = completedJob()
    override val phase: VoiceAgentStartPhase = phaseFactory(request, CoroutineScope(phaseJob), phaseJob)
    override val cleanup = FakeCleanupOperation()
    var startCalls = 0
    var cancelCalls = 0

    override fun start() {
        startCalls += 1
    }

    override fun cancel() {
        cancelCalls += 1
    }
}

internal class FakeCleanupOperation : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    var runCalls = 0

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        runCalls += 1
        return VoiceAgentCleanupResult.Completed
    }
}

internal class InertRouteOwnedSession(
    initialStatus: VoiceSessionStatus,
    override val cleanupOperation: VoiceAgentCleanupOperation,
) : RouteOwnedManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState(session = initialStatus))
    override val routeMetadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isRouteUsable = true
    var resourceCalls = 0

    override fun start() {
        resourceCalls += 1
    }

    override fun interrupt() {
        resourceCalls += 1
    }

    override fun setMuted(value: Boolean) {
        resourceCalls += 1
    }

    override fun reconnect() {
        resourceCalls += 1
    }

    override fun recordDiagnostic(name: String, detail: String) {
        resourceCalls += 1
    }

}

internal val ActiveVoiceAgentCall.testSession: InertRouteOwnedSession
    get() = session as InertRouteOwnedSession

internal fun activeCall(
    request: VoiceAgentCallRequest,
    sessionStatus: VoiceSessionStatus = VoiceSessionStatus.Connected,
): ActiveVoiceAgentCall {
    val cleanup = FakeCleanupOperation()
    val session = InertRouteOwnedSession(sessionStatus, cleanup)
    val callJob = completedJob()
    return ActiveVoiceAgentCall(
        token = Any(),
        request = request,
        route = session.routeMetadata,
        session = session,
        callScope = CoroutineScope(callJob),
        callJob = callJob,
        collector = completedJob(),
        cleanup = cleanup,
    )
}

internal fun activeState(
    call: ActiveVoiceAgentCall,
    sessionState: VoiceAgentUiState = VoiceAgentUiState(session = VoiceSessionStatus.Connected),
): VoiceAgentCallState.Active = VoiceAgentCallState.Active(call, sessionState)

internal fun pending(request: VoiceAgentCallRequest): PendingVoiceAgentStart =
    PendingVoiceAgentStart(Any(), request, listOf(startReply()))

internal fun pendingCancellation(): PendingVoiceAgentCancellation = PendingVoiceAgentCancellation(
    error = CancellationException("caller cancelled"),
    completion = CompletableDeferred(),
)

internal fun startReply() = CompletableDeferred<VoiceAgentCallStartResult>()

internal fun endReply() = CompletableDeferred<VoiceAgentCallEndResult>()

internal fun completedJob(): Job = CompletableDeferred<Unit>().apply { complete(Unit) }

internal fun request(label: String): VoiceAgentCallRequest = VoiceAgentCallRequest(
    conversationId = Uuid.random(),
    transport = VoiceAgentTransport.DirectGemini,
    config = VoiceAgentLaunchConfig(
        hermesVoiceBaseUrl = "https://$label.voice.test",
        credentials = HermesVoiceCredentials(deviceApiKey = "test-key"),
        voiceModelId = label,
        assistantName = "Assistant $label",
        assistantPrompt = "Prompt $label",
        directAccountConfigurationHash = "sha256:" + "a".repeat(64),
    ),
)

internal inline fun <reified T> assertType(value: Any?): T {
    assertTrue("Expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
    return value as T
}

internal fun assertLogicalOwnerCount(
    transition: VoiceAgentCallTransition,
    expected: Int,
) {
    val tokens = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    when (val state = transition.state) {
        VoiceAgentCallState.Idle,
        is VoiceAgentCallState.Starting.Admitting,
        -> Unit
        is VoiceAgentCallState.Starting.Running -> tokens += state.operation.cleanup.token
        is VoiceAgentCallState.Active -> tokens += state.call.cleanup.token
        is VoiceAgentCallState.Stopping -> tokens += state.cleanup.token
        is VoiceAgentCallState.CleanupFailed -> tokens += state.cleanup.token
    }
    transition.effects.forEach { effect ->
        when (effect) {
            is VoiceAgentCallEffect.LaunchStart -> tokens += effect.operation.cleanup.token
            is VoiceAgentCallEffect.CancelStart -> tokens += effect.operation.cleanup.token
            is VoiceAgentCallEffect.RunCleanup -> tokens += effect.cleanup.token
            is VoiceAgentCallEffect.AdmitStart,
            is VoiceAgentCallEffect.ApplyCallStatus,
            is VoiceAgentCallEffect.ApplySessionState,
            is VoiceAgentCallEffect.CompleteCancellations,
            is VoiceAgentCallEffect.CompleteEnds,
            is VoiceAgentCallEffect.CompleteStarts,
            is VoiceAgentCallEffect.CompleteStartsWithCancellation,
            is VoiceAgentCallEffect.Reconnect,
            is VoiceAgentCallEffect.RecordDiagnostic,
            -> Unit
        }
    }
    assertEquals(expected, tokens.size)
}
