package me.rerere.rikkahub.ui.pages.chat.board

import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM guard for the board panel's status-change mapping (SPEC.md M5, maintainer decision #4:
 * NO UI-only validation). The panel never sends a raw target status to the repository — it
 * translates a user's "move this item to <status>" gesture into the explicit [WorkItemAction]
 * the `WorkItemTransitionValidator` judges, exactly like the board tools do. This is the one
 * place that translation lives, so the UI and the tools provably share the same legality path.
 *
 * The mapping is intentionally TOTAL but PARTIAL in result: a gesture that has no single
 * canonical action (e.g. "set to InProgress" from an already-InProgress item) returns null, and
 * the panel simply does nothing — it does not invent an action the validator would then reject.
 */
class BoardActionMappingTest {

    @Test
    fun `moving a pending item to in-progress is a claim`() {
        assertEquals(
            WorkItemAction.Claim,
            boardActionFor(from = WorkItemStatus.Pending, target = WorkItemStatus.InProgress),
        )
    }

    @Test
    fun `moving an in-progress item to completed is a complete`() {
        assertEquals(
            WorkItemAction.Complete,
            boardActionFor(from = WorkItemStatus.InProgress, target = WorkItemStatus.Completed),
        )
    }

    @Test
    fun `moving an in-progress item back to pending is a release`() {
        assertEquals(
            WorkItemAction.Release,
            boardActionFor(from = WorkItemStatus.InProgress, target = WorkItemStatus.Pending),
        )
    }

    @Test
    fun `moving a completed item back to pending is a reopen`() {
        assertEquals(
            WorkItemAction.Reopen,
            boardActionFor(from = WorkItemStatus.Completed, target = WorkItemStatus.Pending),
        )
    }

    @Test
    fun `targeting deleted is always a delete`() {
        assertEquals(
            WorkItemAction.Delete,
            boardActionFor(from = WorkItemStatus.Pending, target = WorkItemStatus.Deleted),
        )
        assertEquals(
            WorkItemAction.Delete,
            boardActionFor(from = WorkItemStatus.InProgress, target = WorkItemStatus.Deleted),
        )
        assertEquals(
            WorkItemAction.Delete,
            boardActionFor(from = WorkItemStatus.Completed, target = WorkItemStatus.Deleted),
        )
    }

    @Test
    fun `a no-op move returns no action`() {
        assertNull(boardActionFor(from = WorkItemStatus.Pending, target = WorkItemStatus.Pending))
        assertNull(boardActionFor(from = WorkItemStatus.InProgress, target = WorkItemStatus.InProgress))
    }

    // The panel never offers a transition the validator forbids: "complete a pending item" or
    // "claim a completed item" have no canonical single action and must map to null so the panel
    // does not push an action the repository would reject (decision #4 keeps the validator
    // authoritative, but the UI must not even attempt an illegal jump).
    @Test
    fun `a non-canonical jump returns no action`() {
        assertNull(boardActionFor(from = WorkItemStatus.Pending, target = WorkItemStatus.Completed))
        assertNull(boardActionFor(from = WorkItemStatus.Completed, target = WorkItemStatus.InProgress))
    }
}
