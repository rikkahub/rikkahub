package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for the workspace-row observation single-writer fix (issue #197 slice 6a
 * review, confirmed mustFix). Exercises the SAME collection topology the VM uses
 * (`getByIdFlow().onEach { state = foldWorkspaceRow(state, row) }.collect`) without viewModelScope /
 * Room — viewModelScope binds Dispatchers.Main which is unavailable on the plain JVM (same constraint
 * as RunEmittingEventTest / VmSafeLaunchTest).
 *
 * The violated invariant: `_state.workspace` must always reflect the LATEST committed row write. The
 * old code broke it by read-modify-reloading — every setToolApproval() launched an independent,
 * unordered coroutine that did `getById` then wrote `_state.workspace`. With two rapid toggles the two
 * reads could resolve out of order, so whichever resumed LAST won the state write — not the latest
 * toggle. The `models the old race` test below reproduces exactly that failure mode; the
 * `single flow writer` tests pin that the new ordered-Flow collector cannot exhibit it.
 */
class WorkspaceRowObserveTest {

    private fun row(approvals: String): WorkspaceEntity = WorkspaceEntity(
        id = "ws-1",
        name = "ws",
        root = "ws-1",
        createdAt = 0L,
        updatedAt = 0L,
        toolApprovals = approvals,
    )

    // FAIL-BEFORE: this models the OLD loadWorkspace() reload — two toggles each launch an independent
    // read-then-write whose reads resolve OUT OF ORDER (the second toggle's read resolves first, the
    // first toggle's stale read resolves last and clobbers it). The stale read wins, so the committed
    // state lags the latest write. This is the race the fix removes; it is asserted here only to prove
    // the failure mode was real, not to keep it.
    @Test
    fun `models the old race - out-of-order reloads let a stale read win`() = runBlocking {
        val latest = row("""{"a":true}""")  // second toggle's freshly-committed row
        val stale = row("""{}""")            // first toggle's earlier snapshot
        var committed: WorkspaceEntity? = null

        val firstReadDone = CompletableDeferred<Unit>()
        coroutineScope {
            // First toggle's reload: its read is gated to resolve LAST.
            launch {
                firstReadDone.await()
                committed = stale // read-modify-write with the stale snapshot — clobbers the fresh one
            }
            // Second toggle's reload: resolves first, writes the fresh row, then unblocks the first.
            launch {
                committed = latest
                firstReadDone.complete(Unit)
            }
        }
        // The old topology commits the STALE row last — the bug.
        assertEquals(stale, committed)
    }

    // FIX: a single ordered Flow collector applies emissions in delivery order, so the final state is
    // always the latest emission regardless of how many writes preceded it. No second writer exists to
    // resolve out of order.
    @Test
    fun `single flow writer - final state equals the latest emission`() = runBlocking {
        val emissions = listOf(row("""{}"""), row("""{"a":true}"""), row("""{"a":true,"b":false}"""))
        var state = WorkspaceDetailState()
        emissions.asFlow()
            .onEach { state = foldWorkspaceRow(state, it) }
            .collect()
        assertEquals(emissions.last(), state.workspace)
    }

    // The fold replaces ONLY the workspace field; browse state (area/path/entries) survives every row
    // re-emission, so an approval toggle does not reset the file listing the user is viewing.
    @Test
    fun `row emission preserves browse state`() = runBlocking {
        val browsing = WorkspaceDetailState(
            area = me.rerere.workspace.WorkspaceStorageArea.LINUX,
            path = "sub/dir",
            loading = true,
            error = "boom",
        )
        val folded = foldWorkspaceRow(browsing, row("""{"a":true}"""))
        assertEquals(browsing.area, folded.area)
        assertEquals(browsing.path, folded.path)
        assertEquals(browsing.loading, folded.loading)
        assertEquals(browsing.error, folded.error)
        assertEquals(row("""{"a":true}"""), folded.workspace)
    }

    // A null emission (row deleted) clears the workspace without disturbing browse state.
    @Test
    fun `null emission clears workspace only`() = runBlocking {
        val state = foldWorkspaceRow(WorkspaceDetailState(path = "p"), row("""{}"""))
        val cleared = foldWorkspaceRow(state, null)
        assertEquals(null, cleared.workspace)
        assertEquals("p", cleared.path)
    }

    // Ordering is preserved even when the collector yields between emissions (the realistic case where
    // each emission is processed across a suspension point), so a late-arriving emission never loses to
    // an earlier one.
    @Test
    fun `collector applies emissions in order across suspension`() = runBlocking {
        val emissions = listOf(row("""{"v":true}"""), row("""{"v":false}"""))
        var state = WorkspaceDetailState()
        val seen = mutableListOf<String>()
        emissions.asFlow()
            .onEach {
                yield()
                state = foldWorkspaceRow(state, it)
                seen.add(state.workspace!!.toolApprovals)
            }
            .collect()
        assertEquals(listOf("""{"v":true}""", """{"v":false}"""), seen)
        assertEquals(emissions.last(), state.workspace)
    }
}
