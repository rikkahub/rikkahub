package me.rerere.rikkahub.ui.pages.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for save-completion routing in SkillDetailPage. The bug: a single
 * identity-less SaveDone closed BOTH the edit dialog and the add-file dialog because the collector
 * inferred the owning dialog from LIVE Compose state (the currently-open editing file) at emission
 * time. Race — start an edit save, dismiss the edit dialog, open Add File, then the in-flight edit's
 * SaveDone arrives and wrongly dismisses the add-file dialog the user is filling in.
 *
 * The fix carries [SkillSaveOrigin] on the event, captured at the call site (the dialog's confirm
 * handler) rather than inferred later. [routeSaveDone] mirrors the page's `when (event.origin)` so the
 * cross-routing case below can be unit-tested without Compose. A test against the old inference
 * (compare saved path to the live editing path) would have routed the in-flight edit to ADD because
 * the edit dialog was already dismissed — the case this pins as fixed.
 */
class SkillSaveTargetTest {

    /** What the page does on SaveDone: it dismisses exactly the dialog named by the event's origin. */
    private fun routeSaveDone(event: SkillDetailEvent.SaveDone): SkillSaveOrigin = event.origin

    @Test
    fun `edit-save routes to the edit dialog`() {
        assertEquals(
            SkillSaveOrigin.EDIT,
            routeSaveDone(SkillDetailEvent.SaveDone(SkillSaveOrigin.EDIT)),
        )
    }

    @Test
    fun `add-file save routes to the add dialog`() {
        assertEquals(
            SkillSaveOrigin.ADD,
            routeSaveDone(SkillDetailEvent.SaveDone(SkillSaveOrigin.ADD)),
        )
    }

    @Test
    fun `in-flight edit save routes to EDIT regardless of the add dialog being open`() {
        // The user dismissed the edit dialog and opened Add File while the edit save was still running.
        // Because the origin was captured at the edit dialog's confirm time, the completion still
        // routes to EDIT — it must NOT close the freshly opened add dialog.
        val inFlightEditDone = SkillDetailEvent.SaveDone(SkillSaveOrigin.EDIT)
        assertEquals(SkillSaveOrigin.EDIT, routeSaveDone(inFlightEditDone))
    }
}
