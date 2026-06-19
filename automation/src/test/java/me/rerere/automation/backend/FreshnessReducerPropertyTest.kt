package me.rerere.automation.backend

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PBT for the pure [FreshnessReducer] (spec §8 / §10). The reducer decides whether a single
 * accessibility event should bump the freshness epoch (stateSeq) and pulse the settle signal. The one
 * suppression is benign non-active SystemUI / status-bar churn; everything else (state changes,
 * active-window content, foreground content, non-system background, or ANY unknown/null
 * classification) fails closed to bump + pulse.
 *
 * Pure & total — no android.*, no I/O — so the rule is fully JVM-testable. Wired into
 * [me.rerere.automation.backend.AutomationBackend]'s event handler by the real runtime; the live
 * classifier may leave fields null (API/permission dependent), so the fail-closed rule is what keeps
 * a classification regression from UNDER-bumping.
 */
class FreshnessReducerPropertyTest {

    // ---- Boundary: WINDOW_STATE_CHANGED always bumps + pulses (a window appeared/vanished/moved). ----
    @Test
    fun `a window state change always bumps and pulses regardless of classification`() {
        runBlocking {
            checkAll(200, arbImpact()) { impact ->
                val d = FreshnessReducer.decide(impact.copy(kind = FreshnessEventKind.WINDOW_STATE_CHANGED))
                assertTrue("WINDOW_STATE_CHANGED must bump", d.bumpEpoch)
                assertTrue("WINDOW_STATE_CHANGED must pulse", d.pulseSettle)
            }
        }
    }

    // ---- Boundary: the ONLY suppress case — a KNOWN non-active system WINDOW_CONTENT_CHANGED. ----
    @Test
    fun `a known non-active system content change is suppressed`() {
        val d = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9,
                eventPackage = "com.android.systemui",
                eventSystemWindow = true,
                activeWindowId = 1,
                activePackage = "com.example.app",
            ),
        )
        assertFalse("non-active system churn must not bump", d.bumpEpoch)
        assertFalse("non-active system churn must not pulse", d.pulseSettle)
    }

    // ---- Boundary: active-window content change bumps + pulses (it may affect the target). ----
    @Test
    fun `an active-window content change bumps and pulses`() {
        val d = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 1,
                eventPackage = "com.example.app",
                eventSystemWindow = false,
                activeWindowId = 1,
                activePackage = "com.example.app",
            ),
        )
        assertTrue(d.bumpEpoch)
        assertTrue(d.pulseSettle)
    }

    // ---- Boundary: foreground-package content change bumps (eventWindowId unknown but package active). ----
    @Test
    fun `a foreground-package content change bumps even with an unknown window id`() {
        val d = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = null,
                eventPackage = "com.example.app",
                eventSystemWindow = false,
                activeWindowId = null,
                activePackage = "com.example.app",
            ),
        )
        assertTrue("foreground content must bump (fail-closed on the null window id)", d.bumpEpoch)
    }

    // ---- Invariant: ANY unknown/null classification fails closed to bump + pulse. ----
    @Test
    fun `any null classification fails closed to bump and pulse`() {
        runBlocking {
            checkAll(200, arbImpact()) { impact ->
                // Force every classification field to null/unknown ⇒ must bump + pulse (never suppress).
                val unknown = impact.copy(
                    kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                    eventWindowId = null,
                    eventPackage = null,
                    eventSystemWindow = null,
                    activeWindowId = null,
                    activePackage = null,
                )
                val d = FreshnessReducer.decide(unknown)
                assertTrue("unknown classification must bump (fail-closed)", d.bumpEpoch)
                assertTrue("unknown classification must pulse (fail-closed)", d.pulseSettle)
            }
        }
    }

    // ---- Invariant: a suppress decision requires the FULL non-active-system signature simultaneously:
    // system flag true, both window ids known and differing, AND both packages known and differing. A
    // foreground-package event (eventPackage == activePackage) is the app's own content and bumps even
    // if it was (mis)classified as a system window. Any deviation ⇒ bump + pulse. ----
    @Test
    fun `suppression requires every non-active-system condition simultaneously`() {
        // system window but SAME window id as active ⇒ NOT non-active ⇒ bump.
        val sameWindow = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 1, eventPackage = "com.android.systemui", eventSystemWindow = true,
                activeWindowId = 1, activePackage = "com.example.app",
            ),
        )
        assertTrue("a system event on the active window must bump", sameWindow.bumpEpoch)

        // system flag, non-active window id, but FOREGROUND package (eventPackage == activePackage) ⇒
        // BUMP: the app's own content must never be muted even if the system flag misfired for it.
        val foregroundPackage = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9, eventPackage = "com.example.app", eventSystemWindow = true,
                activeWindowId = 1, activePackage = "com.example.app",
            ),
        )
        assertTrue("a foreground-package event must bump even if flagged system", foregroundPackage.bumpEpoch)

        // FULL non-active-system signature (system flag, differing window ids, DIFFERING packages) ⇒
        // SUPPRESSED: genuine background status-bar / shade churn the eyes-open binding never matches.
        val nonActiveSystem = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9, eventPackage = "com.android.systemui", eventSystemWindow = true,
                activeWindowId = 1, activePackage = "com.example.app",
            ),
        )
        assertFalse("a fully-classified non-active system window is suppressed", nonActiveSystem.bumpEpoch)

        // non-system background window, non-active ⇒ bump (only SYSTEM non-active is suppressed).
        val nonSystemBackground = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9, eventPackage = "com.other.app", eventSystemWindow = false,
                activeWindowId = 1, activePackage = "com.example.app",
            ),
        )
        assertTrue("a non-system background event must bump", nonSystemBackground.bumpEpoch)
    }

    // ---- Invariant: a null package fails CLOSED. The package fields are part of the suppress
    // signature: a null eventPackage / activePackage means the event identity cannot be fully
    // classified, so the reducer must bump + pulse rather than silently mute the freshness epoch
    // (spec §8 fail-closed rule; resolves the review note that the reducer ignored the package axis). ----
    @Test
    fun `a null package fails closed to bump and pulse`() {
        // Otherwise-suppressible non-active system window, but BOTH packages null ⇒ fail-closed bump.
        val nullPkg = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9, eventPackage = null, eventSystemWindow = true,
                activeWindowId = 1, activePackage = null,
            ),
        )
        assertTrue("a null package must fail closed to bump", nullPkg.bumpEpoch)
        assertTrue("a null package must fail closed to pulse", nullPkg.pulseSettle)

        // Only the active package null (event package known) ⇒ still fail-closed bump.
        val nullActivePkg = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9, eventPackage = "com.android.systemui", eventSystemWindow = true,
                activeWindowId = 1, activePackage = null,
            ),
        )
        assertTrue("a null active package must fail closed to bump", nullActivePkg.bumpEpoch)
    }

    // ---- Invariant: a NEGATIVE window id is unknown (the framework's -1 sentinel), never a real id, so
    // it must fail closed even though it is non-null. The Android adapter normalizes -1 to null before
    // calling, but the reducer must not depend on that — a -1 reaching it must still bump + pulse. ----
    @Test
    fun `a negative window id fails closed to bump and pulse`() {
        // eventWindowId = -1 (unknown) with an otherwise-suppressible non-active system signature.
        val negativeEvent = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = -1, eventPackage = "com.android.systemui", eventSystemWindow = true,
                activeWindowId = 1, activePackage = "com.example.app",
            ),
        )
        assertTrue("a negative event window id must fail closed to bump", negativeEvent.bumpEpoch)
        assertTrue("a negative event window id must fail closed to pulse", negativeEvent.pulseSettle)

        // A negative ACTIVE window id is likewise unknown ⇒ fail-closed bump.
        val negativeActive = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 9, eventPackage = "com.android.systemui", eventSystemWindow = true,
                activeWindowId = -1, activePackage = "com.example.app",
            ),
        )
        assertTrue("a negative active window id must fail closed to bump", negativeActive.bumpEpoch)
    }

    // ---- Metamorphic: bumping the epoch for benign non-target churn must NOT, by itself, stale a
    // targeted act (the binding match — not the epoch — is the freshness signal). This is asserted at
    // the act level in AutomationCoreActPropertyTest; here we only assert the reducer never suppresses
    // an event that could affect the target: an active-window event, ANY unknown/null classification
    // (incl. a null package), or a foreground-package event. ----
    @Test
    fun `reducer never suppresses an active-window, foreground, or unknown event`() {
        runBlocking {
            checkAll(200, arbImpact()) { impact ->
                // A window id is "known" only when non-null AND non-negative (-1 is the framework's
                // unknown sentinel), mirroring the reducer's own classification.
                val isUnknown = impact.eventSystemWindow == null ||
                    impact.eventWindowId == null || impact.eventWindowId < 0 ||
                    impact.activeWindowId == null || impact.activeWindowId < 0 ||
                    impact.eventPackage == null || impact.activePackage == null
                val isActiveWindow = impact.eventWindowId != null && impact.eventWindowId == impact.activeWindowId
                val isForeground = impact.eventPackage != null && impact.eventPackage == impact.activePackage
                val d = FreshnessReducer.decide(impact)
                if (impact.kind == FreshnessEventKind.WINDOW_CONTENT_CHANGED && (isUnknown || isActiveWindow || isForeground)) {
                    assertTrue("an active-window / foreground / unknown event must never be suppressed", d.bumpEpoch)
                }
            }
        }
    }

    private fun arbImpact(): Arb<FreshnessEventImpact> = io.kotest.property.arbitrary.arbitrary {
        FreshnessEventImpact(
            kind = Arb.element(FreshnessEventKind.entries).bind(),
            eventWindowId = Arb.int(-1..20).bind(),
            eventPackage = Arb.element(listOf<String?>(null, "com.example.app", "com.android.systemui", "com.other.app")).bind(),
            eventSystemWindow = Arb.element(listOf<Boolean?>(null, true, false)).bind(),
            activeWindowId = Arb.element(listOf<Int?>(null, 1, 2)).bind(),
            activePackage = Arb.element(listOf<String?>(null, "com.example.app", "com.other.app")).bind(),
        )
    }

    @Suppress("unused") // keep the string arb import meaningful for future expansion
    private fun arbLabel(): Arb<String> = Arb.string(1..8)
}
