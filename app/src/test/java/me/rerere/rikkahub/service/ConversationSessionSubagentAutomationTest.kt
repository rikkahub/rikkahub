package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.Surface
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.automation.AutomationActivationTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Option B (subagent UI automation): a no-automation PARENT can spawn a subagent that mints its own
 * automation lease. The subagent guard is NOT the session's [ConversationSession.activeAutomationGuard],
 * so the session must track it separately or the kill-switch sweep (which fires on
 * [ConversationSession.hasActiveAutomation]) would miss it — leaving a spawned subagent un-revokable.
 */
class ConversationSessionSubagentAutomationTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    private fun guard(): CapabilityGuard = CapabilityGuard(
        capability = Capability.root(
            sessionId = "sub",
            surface = Surface.Scoped(setOf("com.example.app")),
            verbs = setOf(Verb.OBSERVE),
            lease = Lease(expiresAt = Long.MAX_VALUE, maxSteps = 100),
        ),
        clock = TrustClock { 0L },
    )

    @Test
    fun `hasActiveAutomation tracks the subagent guard set`() {
        val s = session()
        assertFalse("a fresh session has no active automation", s.hasActiveAutomation())

        val g = guard()
        s.addSubagentAutomationGuard(g)
        assertTrue("a registered subagent guard counts as active automation", s.hasActiveAutomation())

        s.removeSubagentAutomationGuard(g)
        assertFalse("removing the last subagent guard clears active automation", s.hasActiveAutomation())
    }

    @Test
    fun `revokeAutomation revokes a subagent guard even with no main guard`() {
        // The exact no-automation-parent case: the session has NO activeAutomationGuard, only a
        // spawned subagent's guard. The kill switch must still revoke it.
        val s = session()
        val g = guard()
        s.addSubagentAutomationGuard(g)
        assertFalse(g.isRevoked)

        s.revokeAutomation()

        assertTrue("the kill switch must revoke a subagent guard with no main lease", g.isRevoked)
    }

    @Test
    fun `revokeAutomation revokes both the main and subagent guards`() {
        val s = session()
        val main = guard()
        val sub = guard()
        s.activeAutomationGuard = main
        s.addSubagentAutomationGuard(sub)

        s.revokeAutomation()

        assertTrue("the main lease guard must be revoked", main.isRevoked)
        assertTrue("the subagent lease guard must be revoked", sub.isRevoked)
    }

    // ---- codex P1: the guard must be registered BEFORE the STOP overlay becomes tappable ----
    @Test
    fun `subagent lease registers the guard before exposing the STOP overlay`() {
        val s = session()
        val g = guard()
        // The overlay's showOverlay fires the instant STOP becomes tappable. At that moment the kill
        // switch must already see the guard, or a tap in the gap would revoke nothing.
        var overlaySawActiveAutomation = false
        val tracker = AutomationActivationTracker(
            showOverlay = { overlaySawActiveAutomation = s.hasActiveAutomation(); true },
            hideOverlay = {},
        )

        runBlocking {
            openSubagentAutomationLeaseOnSession(
                session = s,
                guard = g,
                leaseKey = Uuid.random(),
                activation = tracker,
                onNoKillSwitch = { error("the kill switch was reachable; onNoKillSwitch must not run") },
                onActive = {
                    assertTrue("the guard must be registered during the active lease", s.hasActiveAutomation())
                },
            )
        }

        assertTrue(
            "the guard must be registered before the STOP overlay is exposed (no lost-STOP race)",
            overlaySawActiveAutomation,
        )
        assertFalse("the guard is deregistered after the lease", s.hasActiveAutomation())
        assertTrue("the guard is revoked on release", g.isRevoked)
    }

    @Test
    fun `subagent lease fails closed when no STOP overlay is reachable`() {
        val s = session()
        val g = guard()
        val tracker = AutomationActivationTracker(showOverlay = { false }, hideOverlay = {})
        var ranWithoutTools = false

        runBlocking {
            openSubagentAutomationLeaseOnSession(
                session = s,
                guard = g,
                leaseKey = Uuid.random(),
                activation = tracker,
                onNoKillSwitch = { ranWithoutTools = true },
                onActive = { error("automation must not be exposed without a reachable kill switch") },
            )
        }

        assertTrue("no reachable STOP ⇒ run without automation tools", ranWithoutTools)
        assertFalse("the guard is deregistered on the fail-closed path", s.hasActiveAutomation())
        assertTrue("the guard is revoked on the fail-closed path", g.isRevoked)
    }
}
