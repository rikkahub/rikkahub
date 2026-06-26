package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.Surface
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.automation.AutomationActivationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The MAIN-turn automation lease's fail-closed-but-DEGRADE policy (the A2A/headless fix): when the STOP
 * overlay cannot be shown, [resolveMainAutomationGuard] must DEGRADE to a null guard (the turn runs
 * without ui_* tools) instead of throwing and failing the whole turn — while still never exposing ui_*
 * without a reachable kill switch, and never leaking the per-run grant. Mirrors the subagent path's
 * onNoKillSwitch contract ([ConversationSessionSubagentAutomationTest]).
 */
class MainAutomationLeaseDegradeTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    private fun guard(): CapabilityGuard = CapabilityGuard(
        capability = Capability.root(
            sessionId = "main",
            surface = Surface.Scoped(setOf("com.example.app")),
            verbs = setOf(Verb.OBSERVE),
            lease = Lease(expiresAt = Long.MAX_VALUE, maxSteps = 100),
        ),
        clock = TrustClock { 0L },
    )

    // Overlay shows OK ⇒ activate() succeeds ⇒ keep the guard.
    @Test
    fun `overlay available keeps the minted guard registered and live`() {
        val s = session()
        val g = guard()
        s.activeAutomationGuard = g
        val activation = AutomationActivationTracker(showOverlay = { true }, hideOverlay = {})

        val resolved = resolveMainAutomationGuard(s, g, Uuid.random(), activation)

        assertSame("activation success keeps the minted guard", g, resolved)
        assertSame(g, s.activeAutomationGuard)
        assertFalse("a live guard is not revoked", g.isRevoked)
    }

    // Overlay cannot show ⇒ DEGRADE (not throw): null guard, guard revoked, lease state + per-run grant cleared.
    @Test
    fun `overlay unavailable degrades to null guard and clears the per-run grant`() {
        val s = session()
        val g = guard()
        s.activeAutomationGuard = g
        s.pendingAutomationGrant = AutomationGrant(enabled = true, allowedPackages = setOf("com.app"))
        val activation = AutomationActivationTracker(showOverlay = { false }, hideOverlay = {})

        val resolved = resolveMainAutomationGuard(s, g, Uuid.random(), activation)

        assertNull("an unreachable STOP overlay degrades to no guard, never throws", resolved)
        assertTrue("the torn-down guard is revoked", g.isRevoked)
        assertNull("the active guard is cleared on degrade", s.activeAutomationGuard)
        assertNull("the per-run grant must NOT leak past a degraded turn", s.pendingAutomationGrant)
    }

    // No capability/guard ⇒ no automation at all; the overlay is never even attempted.
    @Test
    fun `a null minted guard returns null without attempting the overlay`() {
        val s = session()
        var showOverlayCalls = 0
        val activation = AutomationActivationTracker(showOverlay = { showOverlayCalls++; true }, hideOverlay = {})

        val resolved = resolveMainAutomationGuard(s, mintedGuard = null, conversationId = Uuid.random(), activation = activation)

        assertNull(resolved)
        assertEquals("no guard ⇒ no overlay activation attempt", 0, showOverlayCalls)
    }
}
