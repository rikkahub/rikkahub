package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.Surface
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression for the kill-switch sweep extracted to file level in #360 P1a ([revokeActiveAutomation]).
 * The metamorphic property: over a mix of sessions, the sweep revokes the automation guard AND cancels
 * the generation job of EXACTLY the sessions that have a live automation lease (the main lease OR a
 * subagent lease, Option B), and leaves sessions without automation completely untouched.
 *
 * Pinned with real [ConversationSession]s so the kill-switch policy is testable WITHOUT constructing the
 * full ChatService — the whole point of #360. Mirrors ConversationSessionSubagentAutomationTest's setup.
 */
class RevokeActiveAutomationTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    private fun guard(): CapabilityGuard = CapabilityGuard(
        capability = Capability.root(
            sessionId = "s",
            surface = Surface.Scoped(setOf("com.example.app")),
            verbs = setOf(Verb.OBSERVE),
            lease = Lease(expiresAt = Long.MAX_VALUE, maxSteps = 100),
        ),
        clock = TrustClock { 0L },
    )

    @Test
    fun `revokes and cancels exactly the sessions with active automation`() {
        val mainGuard = guard()
        val mainSession = session().also { it.activeAutomationGuard = mainGuard }
        val mainJob = Job().also { mainSession.setJob(it) }

        val subGuard = guard()
        val subSession = session().also { it.addSubagentAutomationGuard(subGuard) }
        val subJob = Job().also { subSession.setJob(it) }

        val inactiveSession = session()
        val inactiveJob = Job().also { inactiveSession.setJob(it) }

        revokeActiveAutomation(listOf(mainSession, subSession, inactiveSession))

        // Both active sessions: guard revoked AND job cancelled.
        assertTrue("main-lease guard revoked", mainGuard.isRevoked)
        assertTrue("main-lease session job cancelled", mainJob.isCancelled)
        assertTrue("subagent-lease guard revoked", subGuard.isRevoked)
        assertTrue("subagent-lease session job cancelled", subJob.isCancelled)

        // The no-automation session is untouched — its job keeps running.
        assertFalse("a session with no automation must not be cancelled", inactiveJob.isCancelled)
    }

    @Test
    fun `an empty sweep is a no-op`() {
        // No sessions, no throw.
        revokeActiveAutomation(emptyList())
    }

    @Test
    fun `a session whose lease was already revoked stays revoked and is still cancelled`() {
        // Idempotent: re-sweeping a session that already had its guard revoked must not error, and it
        // still cancels the job (the guard reports active until removed; revoke() is idempotent).
        val g = guard().also { it.revoke() }
        val s = session().also { it.activeAutomationGuard = g }
        val job = Job().also { s.setJob(it) }

        revokeActiveAutomation(listOf(s))

        assertTrue(g.isRevoked)
        assertTrue(job.isCancelled)
    }
}
