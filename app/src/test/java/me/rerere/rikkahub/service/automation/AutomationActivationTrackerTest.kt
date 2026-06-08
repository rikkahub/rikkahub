package me.rerere.rikkahub.service.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Pure-logic regression tests for [AutomationActivationTracker] — the refcount that owns the single
 * #187 STOP overlay over a multi-session system.
 *
 * These pin the two confirmed mustFix findings that the previous per-completion-boolean wiring
 * violated:
 *  - **Finding 2 (overlay is a boolean over multi-session):** every automation generation called
 *    `setAutomationActive(true)` and ANY one completion called `setAutomationActive(false)`
 *    unconditionally, so conversation A finishing removed the floating STOP button while
 *    conversation B was still authorized. The tracker fixes this: the overlay is shown iff ≥1
 *    automation session is active (toggled only on the 0→1 / 1→0 edges).
 *  - **Finding 4 (overlay can fail to display, yet ui_observe is exposed):** `show()` could fail to
 *    attach the window; the tracker now reports that via [AutomationActivationTracker.activate]
 *    returning false, so the caller fails closed (does not expose ui_observe).
 */
class AutomationActivationTrackerTest {

    private fun convId(): Uuid = Uuid.random()

    private class FakeOverlay(private val showResult: Boolean = true) {
        var visible = false
            private set
        val showCalls = AtomicInteger(0)
        val hideCalls = AtomicInteger(0)

        fun show(): Boolean {
            showCalls.incrementAndGet()
            if (showResult) visible = true
            return showResult
        }

        fun hide() {
            hideCalls.incrementAndGet()
            visible = false
        }
    }

    private fun tracker(overlay: FakeOverlay) = AutomationActivationTracker(
        showOverlay = overlay::show,
        hideOverlay = overlay::hide,
    )

    @Test
    fun `first activation shows the overlay and reports reachable`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)

        assertTrue("0->1 must report the STOP overlay is reachable", tracker.activate(convId()))
        assertTrue("overlay must be shown on the first active session", overlay.visible)
        assertEquals(1, overlay.showCalls.get())
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun `second concurrent activation does not re-show and keeps the overlay up`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val a = convId()
        val b = convId()

        tracker.activate(a)
        assertTrue("a second session is reachable via the already-shown overlay", tracker.activate(b))

        assertEquals("overlay must be shown exactly once for the 0->1 edge", 1, overlay.showCalls.get())
        assertEquals(2, tracker.activeCount)
        assertTrue(overlay.visible)
    }

    /**
     * THE finding-2 regression. With two sessions active, A finishing must NOT hide the overlay —
     * the any-app kill-switch must remain reachable for the still-active B. The old code hid it
     * unconditionally on A's completion.
     */
    @Test
    fun `releasing one of two active sessions keeps the overlay visible for the other`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val a = convId()
        val b = convId()
        tracker.activate(a)
        tracker.activate(b)

        tracker.deactivate(a)

        assertTrue("overlay must stay up while B is still authorized (finding 2)", overlay.visible)
        assertEquals("must NOT hide until the last session ends", 0, overlay.hideCalls.get())
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun `overlay hides only when the last active session is released`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val a = convId()
        val b = convId()
        tracker.activate(a)
        tracker.activate(b)

        tracker.deactivate(a)
        tracker.deactivate(b)

        assertFalse("1->0 edge hides the overlay", overlay.visible)
        assertEquals(1, overlay.hideCalls.get())
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun `re-entrant activation of the same conversation counts once and releases exactly`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val a = convId()

        tracker.activate(a)
        tracker.activate(a) // idempotent: same conversation, still one active session

        assertEquals("a conversation must count once regardless of repeated activate", 1, tracker.activeCount)

        tracker.deactivate(a)
        assertFalse("a single release of the only session hides the overlay", overlay.visible)
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun `deactivating an unknown conversation is a no-op and never hides a live overlay`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val a = convId()
        tracker.activate(a)

        tracker.deactivate(convId()) // never activated

        assertTrue("an unrelated release must not hide the overlay", overlay.visible)
        assertEquals(0, overlay.hideCalls.get())
        assertEquals(1, tracker.activeCount)
    }

    /**
     * THE finding-4 regression: when the overlay cannot be shown, activation must report
     * fail-closed (false) and NOT count the session as active — so the caller refuses to expose
     * ui_observe without a reachable STOP.
     */
    @Test
    fun `activation fails closed when the overlay cannot be shown`() {
        val overlay = FakeOverlay(showResult = false)
        val tracker = tracker(overlay)

        assertFalse("no reachable STOP => activate must report fail-closed", tracker.activate(convId()))
        assertEquals("a failed 0->1 must leave the active set empty", 0, tracker.activeCount)
        assertFalse(overlay.visible)
    }

    /**
     * THE finding-2 (cancel-relaunch) regression. The tracker is keyed by conversationId, so two
     * generations of the SAME conversation (a regenerate / tool-approval cancels an in-flight
     * automation gen and re-enters handleMessageComplete, minting a fresh guard) share one refcount
     * slot. handleMessageComplete's finally must therefore gate its `deactivate(conversationId)` on
     * `session.activeAutomationGuard === thisGenerationsGuard` — exactly as it already gates the
     * guard-clear — so the superseded old generation's late finally does NOT empty the refcount and
     * hide the floating STOP while the NEW generation is live and observing a foreign app.
     *
     * This models that finally-gate (the production change) against the real tracker: an UNGUARDED
     * old-gen deactivate drops the overlay (the regression), while the IDENTITY-GUARDED one preserves
     * it. Each generation owns the same conversationId; `activeAutomationGuard` points at whichever
     * generation is current.
     */
    @Test
    fun `an unguarded stale deactivate drops the overlay - this is the finding-2 bug`() {
        // EXECUTABLE PROOF OF THE BUG the fix removes. The pre-fix finally called
        // `deactivate(conversationId)` UNCONDITIONALLY. With two generations sharing one conversationId
        // (cancel-relaunch), the superseded old gen's late deactivate empties the conversationId-keyed
        // refcount and hides the floating STOP — while the new gen is still live and observing a
        // foreign app. This reproduces exactly that against the real tracker.
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val conversationId = convId()

        tracker.activate(conversationId)            // old generation
        tracker.activate(conversationId)            // new generation supersedes (same key ⇒ refcount {conv})
        tracker.deactivate(conversationId)          // old gen's UNGUARDED late finally

        assertFalse("the unguarded stale deactivate hides the STOP overlay (the bug)", overlay.visible)
        assertEquals(0, tracker.activeCount)
    }

    /**
     * THE finding-2 (cancel-relaunch) regression — the fix. The tracker is keyed by conversationId, so
     * two generations of the SAME conversation (a regenerate / tool-approval cancels an in-flight
     * automation gen and re-enters handleMessageComplete, minting a fresh guard) share one refcount
     * slot. handleMessageComplete's finally therefore gates its `deactivate(conversationId)` on
     * `session.activeAutomationGuard === thisGenerationsGuard` — exactly as it already gates the
     * guard-clear — so the superseded old generation's late finally does NOT empty the refcount and
     * hide the floating STOP while the NEW generation is live and observing a foreign app.
     *
     * This drives that finally-gate (the production change) against the real tracker; contrast with
     * `an unguarded stale deactivate drops the overlay` above, which shows the same sequence WITHOUT
     * the gate dropping the overlay. `activeAutomationGuard` points at whichever generation is current.
     */
    @Test
    fun `superseded generation must not deactivate the overlay for the live generation`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        val conversationId = convId()

        // Stand-ins for the two CapabilityGuard instances (object identity is all that matters here).
        val oldGuard = Any()
        val newGuard = Any()
        // The session's currently-active guard reference (ChatService.session.activeAutomationGuard).
        var activeAutomationGuard: Any? = null

        // The exact finally-block gate from handleMessageComplete: only the generation that is still
        // the session's active one releases the lease + overlay.
        fun finallyRelease(thisGenerationsGuard: Any) {
            if (activeAutomationGuard === thisGenerationsGuard) {
                activeAutomationGuard = null
                tracker.deactivate(conversationId)
            }
        }

        // --- old generation activates ---
        activeAutomationGuard = oldGuard
        assertTrue(tracker.activate(conversationId))
        assertTrue("overlay up for the old generation", overlay.visible)

        // --- new generation supersedes it (cancel-relaunch on the same conversation): it mints a new
        // guard, becomes the active one, and re-activates (refcount stays {conv}, overlay already up).
        activeAutomationGuard = newGuard
        assertTrue("re-activation on the same conversation is reachable via the live overlay", tracker.activate(conversationId))

        // --- the OLD generation's cancelled job now runs its finally LATE. With the identity gate it
        // is a no-op (its guard is no longer active), so the overlay survives for the new generation.
        finallyRelease(oldGuard)

        assertTrue("the live generation's STOP overlay must survive the stale old finally", overlay.visible)
        assertEquals("the superseded finally must not hide the overlay", 0, overlay.hideCalls.get())
        assertEquals(1, tracker.activeCount)

        // --- when the NEW (live) generation finishes, its finally IS the active one ⇒ 1→0 hide. ---
        finallyRelease(newGuard)
        assertFalse("the last live generation releases the overlay", overlay.visible)
        assertEquals(1, overlay.hideCalls.get())
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun `concurrent activate and deactivate of distinct sessions never lose the overlay`() {
        val overlay = FakeOverlay()
        val tracker = tracker(overlay)
        // Keep one anchor session active for the whole run so the overlay should never drop.
        val anchor = convId()
        tracker.activate(anchor)

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                val id = convId()
                repeat(200) {
                    tracker.activate(id)
                    tracker.deactivate(id)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("only the anchor remains", 1, tracker.activeCount)
        assertTrue("overlay must never have been hidden while the anchor stayed active", overlay.visible)
    }
}
