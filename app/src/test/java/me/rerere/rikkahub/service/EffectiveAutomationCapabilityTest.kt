package me.rerere.rikkahub.service

import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Surface
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11 root-cause: `ChatService.withAutomationLease` used to mint the guard with a HARD-CODED
 * `surface = emptySet()`, so `CapabilityGuard` DENIED every request regardless of any user grant —
 * the automation subsystem was inert. The fix derives the lease `Capability` from the effective
 * grant via the pure [effectiveAutomationCapability], which maps the `:app` `AutomationGrant` mirror
 * onto the kernel grant and runs `toCapability`.
 *
 * #187 v2 activation policy (finding 1): [Assistant.uiAutomationEnabled] is the single master gate
 * for UI automation. The per-run pending grant can override the assistant default grant, but it
 * cannot bypass the master switch. With the switch off, no capability is minted from either source.
 *
 * These properties pin the seam: (1) no grant ⇒ null capability ⇒ NO guard ⇒ DENY (no regression);
 * (2) per-run grant overrides the assistant default when the switch is on; (3) both grant sources
 * stay inert with the switch off; (4) a real grant fills the surface/verbs the user approved while
 * SUBMIT stays withheld.
 */
class EffectiveAutomationCapabilityTest {

    private val sessionId = "session-1"
    private val now = 1_000_000L

    @Test
    fun `absent grant (both default) derives no capability so the guard denies all`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )

        assertNull("a default/empty grant must NOT mint a usable capability", cap)
    }

    @Test
    fun `enabled-but-empty-surface grant still derives no capability`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = emptySet(),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )

        assertNull("enabled grant with no approved package is still deny-all", cap)
    }

    @Test
    fun `zero TTL or zero steps derives no capability`() {
        val zeroTtl = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 0,
                maxSteps = 50,
            ),
            assistantGrant = AutomationGrant(),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )
        val zeroSteps = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 0,
            ),
            assistantGrant = AutomationGrant(),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )

        assertNull("zero TTL is already-expired ⇒ deny-all", zeroTtl)
        assertNull("zero steps is no-admit ⇒ deny-all", zeroSteps)
    }

    @Test
    fun `grant for package P with OBSERVE+TAP fills the surface and those verbs`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )!!

        assertEquals(Surface.Scoped(setOf("com.example.target")), cap.surface)
        assertEquals(setOf(Verb.OBSERVE, Verb.TAP), cap.verbs)
        assertEquals(sessionId, cap.sessionId)
        assertEquals(now + 5L * 60_000L, cap.lease.expiresAt)
        assertEquals(50, cap.lease.maxSteps)
    }

    @Test
    fun `SUBMIT sink is stripped even when the grant lists it`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.SET_TEXT),
                sinks = setOf(AutomationSink.TYPE_INTO, AutomationSink.SUBMIT),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )!!

        assertTrue("TYPE_INTO is in budget", Sink.TYPE_INTO in cap.sinkBudget)
        assertTrue("SUBMIT must never reach the lease", Sink.SUBMIT !in cap.sinkBudget)
    }

    @Test
    fun `per-run pending grant overrides the assistant default grant`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.perrun.app"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 3,
                maxSteps = 10,
            ),
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.assistant.default"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                ttlMinutes = 30,
                maxSteps = 256,
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )!!

        assertEquals("per-run surface wins", Surface.Scoped(setOf("com.perrun.app")), cap.surface)
        assertEquals(now + 3L * 60_000L, cap.lease.expiresAt)
        assertEquals(10, cap.lease.maxSteps)
    }

    @Test
    fun `absent per-run grant falls back to the assistant default grant`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.assistant.default"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 7,
                maxSteps = 20,
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
        )!!

        assertEquals(Surface.Scoped(setOf("com.assistant.default")), cap.surface)
    }

    // --- #187 v2 activation policy: the master switch gates every grant source (finding 1) ---

    @Test
    fun `a usable per-run grant stays inert when the master switch is off`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.perrun.app"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            assistantGrant = AutomationGrant(),
            masterSwitchEnabled = false,
            sessionId = sessionId,
            now = now,
        )

        assertNull(
            "the master switch gates every automation grant source — a pending grant must not " +
                "mint a capability while UI automation is disabled",
            cap,
        )
    }

    @Test
    fun `the standing grant stays inert when the master switch is off`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.assistant.default"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 30,
                maxSteps = 256,
            ),
            masterSwitchEnabled = false,
            sessionId = sessionId,
            now = now,
        )

        assertNull(
            "the master switch gates the STANDING grant — with it off, the standing default " +
                "never activates",
            cap,
        )
    }

    @Test
    fun `with the switch off no grant source activates even when both are usable`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.perrun.app"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.assistant.default"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                ttlMinutes = 30,
                maxSteps = 256,
            ),
            masterSwitchEnabled = false,
            sessionId = sessionId,
            now = now,
        )

        assertNull(
            "the master switch must gate the whole effective-grant expression, including the " +
                "pending-grant branch",
            cap,
        )
    }

    // --- YOLO ("bypass all restriction"): acknowledgement gate + pending-cannot-widen (codex P0-4) ---

    private fun yoloGrant() = AutomationGrant(
        enabled = true,
        // YOLO needs no allowedPackages — the surface is unbounded. Left empty on purpose to prove the
        // empty-surface deny does NOT apply to a YOLO grant.
        allowedPackages = emptySet(),
        ttlMinutes = 30,
        maxSteps = 256,
        yolo = true,
    )

    @Test
    fun `an acknowledged standing YOLO grant derives an unbounded host-inclusive capability`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = yoloGrant(),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
            yoloAcknowledged = true,
        )!!

        assertEquals("YOLO surface is unbounded", Surface.Unbounded, cap.surface)
        assertTrue("YOLO includes the host", cap.includeHost)
        assertTrue("YOLO grants every verb", cap.verbs.containsAll(Verb.entries.toSet()))
        assertTrue("YOLO grants SUBMIT (not stripped)", cap.sinkBudget.contains(Sink.SUBMIT))
    }

    @Test
    fun `a standing YOLO grant without the danger acknowledgement degrades to deny-all`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = yoloGrant(), // empty allowedPackages
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
            yoloAcknowledged = false,
        )

        // Without acknowledgement YOLO is stripped, leaving an enabled-but-empty-surface scoped grant
        // ⇒ no usable capability. The dangerous mode is unreachable until the user accepts it.
        assertNull("unacknowledged YOLO must not mint a capability", cap)
    }

    @Test
    fun `a standing YOLO grant with a scoped fallback degrades to that scope when unacknowledged`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = yoloGrant().copy(
                allowedPackages = setOf("com.scoped.fallback"),
                verbs = setOf(AutomationVerb.OBSERVE),
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
            yoloAcknowledged = false,
        )!!

        assertEquals(
            "unacknowledged YOLO falls back to the scoped whitelist, never unbounded",
            Surface.Scoped(setOf("com.scoped.fallback")),
            cap.surface,
        )
        assertTrue("the scoped fallback never includes the host", !cap.includeHost)
    }

    @Test
    fun `a pending grant can never widen to YOLO`() {
        val cap = effectiveAutomationCapability(
            // A per-run grant that tries to turn YOLO on — even with the danger acknowledged, the
            // pending path must be stripped to scoped (only the STANDING assistant grant may be YOLO).
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.perrun.app"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
                yolo = true,
            ),
            assistantGrant = AutomationGrant(),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
            yoloAcknowledged = true,
        )!!

        assertEquals(
            "a pending grant must derive the scoped surface, never Unbounded",
            Surface.Scoped(setOf("com.perrun.app")),
            cap.surface,
        )
        assertTrue("a pending grant can never include the host", !cap.includeHost)
    }

    @Test
    fun `an unsupported flavor strips YOLO even when acknowledged`() {
        // The Play flavor passes yoloSupported=false. A restored/imported settings.json carrying both
        // yolo=true AND automationYoloAcknowledged=true must STILL not mint an unrestricted capability —
        // the derivation chokepoint is the runtime half of the sideload-only boundary (codex P1).
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = yoloGrant().copy(
                allowedPackages = setOf("com.scoped.fallback"),
                verbs = setOf(AutomationVerb.OBSERVE),
            ),
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
            yoloAcknowledged = true,
            yoloSupported = false,
        )!!

        assertEquals(
            "a flavor that does not support YOLO must fall back to the scoped surface, never Unbounded",
            Surface.Scoped(setOf("com.scoped.fallback")),
            cap.surface,
        )
        assertTrue("an unsupported flavor never includes the host", !cap.includeHost)
    }

    @Test
    fun `an unsupported flavor with an empty-scope YOLO grant derives nothing`() {
        // The exact Play-restore exploit shape: yolo + acknowledged but no scoped fallback packages.
        // Stripping yolo leaves an enabled-but-empty-surface grant ⇒ no capability (deny-all).
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = yoloGrant(), // empty allowedPackages
            masterSwitchEnabled = true,
            sessionId = sessionId,
            now = now,
            yoloAcknowledged = true,
            yoloSupported = false,
        )
        assertNull("an unsupported flavor must not mint a YOLO capability from imported state", cap)
    }
}
