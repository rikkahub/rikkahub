package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic test for [appendChatError], the bounded-append helper that decides how the chat error
 * list grows when a new error arrives. Keeping the decision pure lets it run on the JVM without any
 * Android / Firebase / Room dependency, mirroring [shouldAutoCompact] and [shouldRenewWakeLock].
 *
 * The invariant this guards: repeated provider failures must NOT grow the error list without bound.
 * The list keeps at most [MAX_CHAT_ERRORS] entries; when full, the OLDEST error is dropped so the
 * newest error is always retained (takeLast semantics). Before the fix, addError used an unbounded
 * `it + ChatError(...)`, so a pathological failure loop could accumulate errors forever.
 */
class ChatErrorCapTest {

    private fun error(title: String? = null) =
        ChatError(title = title, error = RuntimeException("e"))

    @Test
    fun `appending past the cap stays bounded`() {
        // Regression guard: start from a full list of distinct errors, append one more, and assert the
        // result is capped — not MAX_CHAT_ERRORS + 1. Fails on the old unbounded `it + ChatError(...)`.
        val full = (0 until MAX_CHAT_ERRORS).map { error(title = "e$it") }
        val result = appendChatError(full, error(title = "overflow"))
        assertEquals(MAX_CHAT_ERRORS, result.size)
        // Newest is always retained.
        assertEquals("overflow", result.last().title)
    }

    @Test
    fun `oldest error is dropped first`() {
        // Fold 5 errors through a cap of 3; only the last 3 (e2, e3, e4) survive, in order.
        val inputs = (0 until 5).map { error(title = "e$it") }
        val result = inputs.fold(emptyList<ChatError>()) { acc, e -> appendChatError(acc, e, max = 3) }
        assertEquals(3, result.size)
        assertEquals(listOf("e2", "e3", "e4"), result.map { it.title })
        // e0 (first appended) was dropped; e4 (last appended) is present.
        assertFalse(result.any { it.title == "e0" })
        assertTrue(result.any { it.title == "e4" })
    }

    @Test
    fun `under the cap appends in order without dropping`() {
        val a = error(title = "a")
        val b = error(title = "b")
        val result = appendChatError(listOf(a), b)
        assertEquals(listOf(a, b), result)
    }
}
