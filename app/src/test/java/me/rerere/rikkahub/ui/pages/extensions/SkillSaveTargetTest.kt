package me.rerere.rikkahub.ui.pages.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for save-completion routing in SkillDetailPage / SkillsPage.
 *
 * Original bug (cross-CATEGORY): a single identity-less SaveDone closed BOTH the edit and add dialogs.
 * Carrying [SkillSaveOrigin] on the event fixed that — but origin is a CATEGORY, not an INVOCATION
 * instance. The reopened bug (within-CATEGORY race): start an edit save of file A, dismiss the edit
 * dialog, open the edit dialog again for file B; A's in-flight SaveDone(EDIT) then dismissed B's dialog
 * because both share origin EDIT. The save runs on viewModelScope, so dismissing the dialog does NOT
 * cancel the save — the late completion is real and must be routed to the instance that started it.
 *
 * Fix: [SkillSaveTarget] carries an opaque per-confirm [SkillSaveTarget.token] minted by
 * [SkillSaveTokens]. The page dismisses a dialog only when the completion's token still matches the
 * token of the currently-open instance. [shouldDismiss] mirrors the page's collector decision so the
 * race can be unit-tested without Compose.
 */
class SkillSaveTargetTest {

    /**
     * Mirror of the page collector's decision for a SaveDone of [completion]: dismiss the dialog only
     * when the completion's token still matches the token the page recorded for the currently-open
     * dialog of that category ([openEditToken] / [openAddToken]; null = no open dialog of that kind).
     */
    private fun shouldDismiss(
        completion: SkillSaveTarget,
        openEditToken: Long?,
        openAddToken: Long?,
    ): Boolean = when (completion.origin) {
        SkillSaveOrigin.EDIT -> completion.token == openEditToken
        SkillSaveOrigin.ADD -> completion.token == openAddToken
    }

    @Test
    fun `tokens are unique and monotonic`() {
        val tokens = SkillSaveTokens()
        val a = tokens.next()
        val b = tokens.next()
        assertNotEquals(a, b)
        assertTrue(b > a)
    }

    @Test
    fun `edit-save dismisses the edit dialog it started`() {
        val tokens = SkillSaveTokens()
        val t = tokens.next()
        // The edit dialog that started the save is still open with token t.
        assertTrue(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, t),
                openEditToken = t,
                openAddToken = null,
            )
        )
    }

    @Test
    fun `add-save dismisses the add dialog it started`() {
        val tokens = SkillSaveTokens()
        val t = tokens.next()
        assertTrue(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.ADD, t),
                openEditToken = null,
                openAddToken = t,
            )
        )
    }

    @Test
    fun `edit-save never dismisses an add dialog (cross-category)`() {
        val tokens = SkillSaveTokens()
        val editToken = tokens.next()
        val addToken = tokens.next()
        assertFalse(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, editToken),
                openEditToken = null,
                openAddToken = addToken,
            )
        )
    }

    @Test
    fun `in-flight edit-save of file A does NOT dismiss the edit dialog reopened for file B`() {
        // Repro from the reopened finding: confirm edit of A (token A), dismiss the dialog, reopen the
        // edit dialog for B (token B). A's save is still running on viewModelScope and now completes.
        val tokens = SkillSaveTokens()
        val tokenA = tokens.next()
        val tokenB = tokens.next()
        // With category-only routing this returned true and wrongly closed B's dialog. With instance
        // identity, A's completion no longer matches the open dialog's token (B), so it is ignored.
        assertFalse(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, tokenA),
                openEditToken = tokenB,
                openAddToken = null,
            )
        )
        // And B's own completion still dismisses B.
        assertTrue(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, tokenB),
                openEditToken = tokenB,
                openAddToken = null,
            )
        )
    }

    @Test
    fun `a completion after its dialog was dismissed (none reopened) dismisses nothing`() {
        val tokens = SkillSaveTokens()
        val tokenA = tokens.next()
        // Dialog dismissed -> page cleared its token to null. A's late completion matches nothing.
        assertFalse(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, tokenA),
                openEditToken = null,
                openAddToken = null,
            )
        )
    }

    @Test
    fun `SkillSaveTarget carries the origin captured at the call site`() {
        assertEquals(
            SkillSaveOrigin.EDIT,
            SkillSaveTarget(SkillSaveOrigin.EDIT, 0L).origin,
        )
        assertEquals(
            SkillSaveOrigin.ADD,
            SkillSaveTarget(SkillSaveOrigin.ADD, 0L).origin,
        )
    }

    /**
     * Reopened-after-recreation finding (the mustFix this commit fixes): token routing only survives a
     * config change if BOTH the page's recorded token AND the token authority outlive it. The page side
     * is now rememberSaveable; the authority side is now VM-owned ([SkillsVM.nextSaveToken] /
     * [SkillDetailVM.nextSaveToken]) instead of a page-held `remember { SkillSaveTokens() }`.
     *
     * Why this matters and why two authorities can collide: a page-held authority is re-created on
     * recreation and its counter resets to 0, while rememberSaveable dialog state survives — so a
     * stale in-flight save (token 0) and a post-recreation re-confirm (token 0 again) share a value and
     * the late completion dismisses the WRONG, reopened dialog. The case below pins that collision:
     * routing is only safe because the surviving VM authority never re-mints a live token.
     *
     * The cross-recreation coroutine/Compose path itself is not JVM-unit-testable here (no Robolectric
     * in this source set; viewModelScope binds Dispatchers.Main), so the bug — a token-LIFETIME bug — is
     * pinned at its routing-safety contract: under token reuse, routing mis-fires.
     */
    @Test
    fun `routing mis-fires when a reset authority reuses a live token (why the authority must survive)`() {
        // Old behavior: page-held authority A starts save A (token 0) and is then thrown away on
        // recreation; a fresh authority B (re-remembered, counter 0) mints the reopened dialog's token.
        val authorityBeforeRecreation = SkillSaveTokens()
        val staleInFlightToken = authorityBeforeRecreation.next() // save A still running on viewModelScope

        val authorityAfterRecreation = SkillSaveTokens() // page-held authorities do NOT survive recreation
        val reopenedDialogToken = authorityAfterRecreation.next()

        // Two independent authorities both start at 0 -> the value is reused across the boundary.
        assertEquals(staleInFlightToken, reopenedDialogToken)
        // ...and that reuse makes save A's late completion dismiss the reopened dialog (the bug).
        assertTrue(
            "reused token => stale completion wrongly dismisses the reopened dialog",
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.ADD, staleInFlightToken),
                openEditToken = null,
                openAddToken = reopenedDialogToken,
            )
        )
    }

    @Test
    fun `a single surviving authority never reuses a token, so no stale completion can collide`() {
        // Fixed behavior: ONE VM-owned authority survives the recreation, so the save started before and
        // the re-confirm after draw from the same monotonic counter and cannot share a value. Routing is
        // then never tricked: the stale completion's token matches no open dialog.
        val survivingAuthority = SkillSaveTokens()
        val staleInFlightToken = survivingAuthority.next() // save A, dialog open with this token
        // ... config change: VM + authority survive, dialog token restored from rememberSaveable ...
        val reopenedDialogToken = survivingAuthority.next() // re-confirm draws the NEXT value
        assertNotEquals(staleInFlightToken, reopenedDialogToken)
        assertFalse(
            "surviving authority => stale completion's token matches no open dialog",
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.ADD, staleInFlightToken),
                openEditToken = null,
                openAddToken = reopenedDialogToken,
            )
        )
    }
}
