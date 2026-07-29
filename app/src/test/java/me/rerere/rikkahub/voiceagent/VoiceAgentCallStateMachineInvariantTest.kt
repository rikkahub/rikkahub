package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceAgentCallStateMachineInvariantTest {
    @Test
    fun `projections expose only exact active identity`() {
        val request = request("projection")
        val pending = pending(request)
        val operation = FakeStartOperation(request)
        val active = activeCall(request)
        val cleanup = FakeCleanupOperation()
        val failure = IllegalStateException("cleanup")

        val cases = listOf(
            VoiceAgentCallState.Idle to VoiceAgentCallLifecycle.Idle,
            VoiceAgentCallState.Starting.Admitting(pending) to
                VoiceAgentCallLifecycle.Starting(request.conversationId),
            VoiceAgentCallState.Starting.Running(pending, operation) to
                VoiceAgentCallLifecycle.Starting(request.conversationId),
            activeState(active) to VoiceAgentCallLifecycle.Active(request.conversationId),
            VoiceAgentCallState.Stopping.ForReplacement(
                cleanup,
                pending,
                emptyList(),
                emptyList(),
                emptyList(),
            ) to VoiceAgentCallLifecycle.Stopping(request.conversationId),
            VoiceAgentCallState.Stopping.ForEnd(
                cleanup,
                emptyList(),
                emptyList(),
                emptyList(),
            ) to VoiceAgentCallLifecycle.Stopping(null),
            VoiceAgentCallState.CleanupFailed(cleanup, failure) to VoiceAgentCallLifecycle.CleanupFailed(failure),
        )

        cases.forEach { (state, lifecycle) ->
            assertEquals(lifecycle, state.lifecycle)
            assertEquals(
                (state as? VoiceAgentCallState.Active)?.call?.request?.let { request ->
                    ActiveVoiceAgentIdentity(request.conversationId, request.transport)
                },
                state.activeIdentity,
            )
        }
    }

    @Test
    fun `pending start rejects empty waiter groups`() {
        assertThrows(IllegalArgumentException::class.java) {
            PendingVoiceAgentStart(Any(), request("empty"), emptyList())
        }
    }

    @Test
    fun `normal transitions retain zero or one logical resource owner`() {
        val first = request("owner-first")
        val second = request("owner-second")
        val pending = pending(first)
        val operation = FakeStartOperation(first)
        val admitted = reduceVoiceAgentCallState(
            VoiceAgentCallState.Idle,
            VoiceAgentCallEvent.StartRequested(pending),
        )
        assertLogicalOwnerCount(admitted, 0)

        val running = reduceVoiceAgentCallState(
            admitted.state,
            VoiceAgentCallEvent.StartAdmitted(pending.token, operation),
        )
        assertLogicalOwnerCount(running, 1)

        val replacingStartup = reduceVoiceAgentCallState(
            running.state,
            VoiceAgentCallEvent.StartRequested(pending(second)),
        )
        assertLogicalOwnerCount(replacingStartup, 1)

        val cleanupComplete = reduceVoiceAgentCallState(
            replacingStartup.state,
            VoiceAgentCallEvent.CleanupFinished(operation.cleanup, VoiceAgentCleanupResult.Completed),
        )
        assertLogicalOwnerCount(cleanupComplete, 0)

        val active = activeCall(first)
        val replacingActive = reduceVoiceAgentCallState(
            activeState(active),
            VoiceAgentCallEvent.StartRequested(pending(second)),
        )
        assertLogicalOwnerCount(replacingActive, 1)

        val failure = reduceVoiceAgentCallState(
            replacingActive.state,
            VoiceAgentCallEvent.CleanupFinished(
                active.cleanup,
                VoiceAgentCleanupResult.Failed(IllegalStateException("cleanup")),
            ),
        )
        assertLogicalOwnerCount(failure, 1)
    }

    @Test
    fun `reducer never invokes operation session cleanup jobs or deferreds`() {
        val request = request("pure")
        val operation = FakeStartOperation(request)
        val active = activeCall(request)
        val startReply = startReply()
        val endReply = endReply()
        val pending = PendingVoiceAgentStart(Any(), request, listOf(startReply))

        reduceVoiceAgentCallState(VoiceAgentCallState.Idle, VoiceAgentCallEvent.StartRequested(pending))
        reduceVoiceAgentCallState(
            VoiceAgentCallState.Starting.Running(pending, operation),
            VoiceAgentCallEvent.EndRequested(endReply),
        )
        reduceVoiceAgentCallState(
            activeState(active),
            VoiceAgentCallEvent.StartRequested(pending(request("different"))),
        )

        assertEquals(0, operation.startCalls)
        assertEquals(0, operation.cancelCalls)
        assertEquals(0, operation.cleanup.runCalls)
        assertEquals(0, active.testSession.resourceCalls)
        assertFalse(startReply.isCompleted)
        assertFalse(endReply.isCompleted)
    }
}
