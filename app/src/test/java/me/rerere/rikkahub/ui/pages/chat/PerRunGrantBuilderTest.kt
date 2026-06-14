package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10: the in-chat per-run grant sheet builds a transient [me.rerere.rikkahub.data.model.AutomationGrant]
 * from the foreground package + selected verbs + TTL. The builder is the pure confirm-logic of
 * the bottom sheet (top-level, JVM-testable like [shouldBlockSubmitForMissingModel]); the ViewModel
 * writes its result to `ConversationSession.pendingAutomationGrant`.
 *
 * The load-bearing invariant proven here is sink/verb consistency: a write/navigation verb only
 * authorizes its action if the matching sink is in the grant's budget (the kernel guard DENYs a
 * non-null sink absent from the budget). The sheet only exposes verb selection, so the builder must
 * DERIVE the required sinks from the verbs — never take a caller-supplied sink set that could drift
 * from (or lie about) the verbs. SET_TEXT ⇒ TYPE_INTO, GLOBAL ⇒ GLOBAL_NAV; SUBMIT is never minted
 * (submit-class stays the stricter separate opt-in the kernel withholds).
 */
class PerRunGrantBuilderTest {

    @Test
    fun `confirming for a foreground package mints an enabled grant scoped to exactly that package`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertTrue("a confirmed grant is enabled", grant!!.enabled)
        assertEquals(setOf("com.example.target"), grant.allowedPackages)
        assertEquals(setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP), grant.verbs)
        assertEquals(5, grant.ttlMinutes)
        assertEquals(50, grant.maxSteps)
    }

    @Test
    fun `selecting SET_TEXT derives the TYPE_INTO sink so the write actually authorizes`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.SET_TEXT),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertTrue(
            "SET_TEXT requires TYPE_INTO in budget or the kernel guard denies every write",
            grant!!.sinks.contains(AutomationSink.TYPE_INTO),
        )
    }

    @Test
    fun `selecting GLOBAL derives the GLOBAL_NAV sink so the navigation actually authorizes`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.GLOBAL),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertTrue(
            "GLOBAL requires GLOBAL_NAV in budget or the kernel guard denies every global act",
            grant!!.sinks.contains(AutomationSink.GLOBAL_NAV),
        )
    }

    @Test
    fun `sink-less verbs derive no sinks`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP, AutomationVerb.SCROLL),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertTrue(
            "OBSERVE/TAP/SCROLL carry no sink — an ordinary tap is verb-gated only",
            grant!!.sinks.isEmpty(),
        )
    }

    @Test
    fun `SUBMIT is never minted even when TAP is selected`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.TAP, AutomationVerb.SET_TEXT, AutomationVerb.GLOBAL),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertFalse(
            "SUBMIT must never reach a per-run grant — it is the stricter separate opt-in",
            grant!!.sinks.contains(AutomationSink.SUBMIT),
        )
    }

    @Test
    fun `a null or blank foreground package yields no grant`() {
        assertNull(
            "no foreground package = nothing to scope the grant to = no grant",
            buildPerRunGrant(
                foregroundPackage = null,
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
        )
        assertNull(
            buildPerRunGrant(
                foregroundPackage = "   ",
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
        )
    }
}
