package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure logic of the interactive Termux session tools: tmux argv
 * construction, the settle / wait_for read decision, session-list parsing, and
 * not-found detection. The Android RUN_COMMAND IO is verified on-device.
 */
class TermuxSessionToolTest {

    @Test
    fun sessionName_hasPrefix_andSanitizes() {
        assertTrue(TmuxOps.sessionName(null).startsWith("rk_"))
        val named = TmuxOps.sessionName("my pc!")
        assertTrue(named.startsWith("rk_my_pc_"))
        assertTrue(named.none { it == ' ' || it == '!' })
    }

    @Test
    fun sessionName_isUnique() {
        assertTrue(TmuxOps.sessionName(null) != TmuxOps.sessionName(null))
    }

    @Test
    fun argvBuilders_areLiteralAndSafe() {
        assertEquals(
            listOf("new-session", "-d", "-s", "rk_x", "-x", "200", "-y", "50"),
            TmuxOps.startArgv("rk_x", 200, 50).toList()
        )
        assertEquals(
            listOf("send-keys", "-t", "rk_x", "-l", "--", "echo 'hi there'"),
            TmuxOps.sendTextArgv("rk_x", "echo 'hi there'").toList()
        )
        assertEquals(
            listOf("send-keys", "-t", "rk_x", "C-c", "Enter"),
            TmuxOps.sendKeysArgv("rk_x", listOf("C-c", "Enter")).toList()
        )
        assertEquals(
            listOf("capture-pane", "-t", "rk_x", "-p", "-S", "-200"),
            TmuxOps.capturePaneArgv("rk_x", 200).toList()
        )
        assertEquals(listOf("kill-session", "-t", "rk_x"), TmuxOps.killArgv("rk_x").toList())
    }

    @Test
    fun waitFor_matchesSubstringAndRegex() {
        assertTrue(waitForMatches("Enter password:", "password:"))
        assertTrue(waitForMatches("user@host:~$ ", "\\$ "))
        assertTrue(!waitForMatches("nothing here", "password:"))
        // invalid regex falls back to substring
        assertTrue(waitForMatches("a[b", "a[b"))
    }

    @Test
    fun evaluatePoll_returnsMatchedAsSoonAsWaitForHits() {
        val samples = listOf(
            PaneSample(0, "loading"),
            PaneSample(200, "Enter password:"),
        )
        val r = evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = "password:")
        assertEquals(PollResult.Reason.MATCHED, (r as PollResult.Done).reason)
    }

    @Test
    fun evaluatePoll_settlesWhenStableLongEnough() {
        val samples = listOf(
            PaneSample(0, "a"),
            PaneSample(200, "b"),
            PaneSample(400, "b"),
            PaneSample(900, "b"),
        )
        val r = evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = null)
        assertEquals(PollResult.Reason.SETTLED, (r as PollResult.Done).reason)
    }

    @Test
    fun evaluatePoll_continuesWhileStillChanging() {
        val samples = listOf(PaneSample(0, "a"), PaneSample(200, "b"))
        assertEquals(PollResult.Continue, evaluatePoll(samples, 600, 20_000, null))
    }

    @Test
    fun evaluatePoll_timesOut() {
        val samples = listOf(PaneSample(0, "a"), PaneSample(20_000, "b"))
        val r = evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = null)
        assertEquals(PollResult.Reason.TIMEOUT, (r as PollResult.Done).reason)
    }

    @Test
    fun parseSessions_keepsOnlyRkPrefixed() {
        val out = "rk_a\t1700000000\t1700000100\nuserwork\t1700000000\t1700000050\nrk_b\t1700000200\t1700000200\n"
        val sessions = parseSessions(out)
        assertEquals(listOf("rk_a", "rk_b"), sessions.map { it.name })
        assertEquals(1700000100L, sessions[0].lastActivity)
    }

    @Test
    fun parseSessions_emptyOrGarbage() {
        assertTrue(parseSessions("").isEmpty())
        assertTrue(parseSessions("malformed-line-no-tabs").isEmpty())
    }

    @Test
    fun isSessionNotFound_detectsTmuxErrors() {
        assertTrue(isSessionNotFound("can't find session: rk_x"))
        assertTrue(isSessionNotFound("no server running on /tmp/tmux-0/default"))
        assertTrue(!isSessionNotFound("some other error"))
    }

    @Test
    fun staleSessionsToReap_reapsOnlyOlderThanTtl() {
        val now = 1_700_000_000L // epoch seconds
        val ttlMs = 6L * 60 * 60 * 1000 // 6h
        val fresh = TmuxSessionInfo("rk_fresh", created = now - 10, lastActivity = now - 60)
        val stale = TmuxSessionInfo("rk_stale", created = now - 100000, lastActivity = now - 7 * 60 * 60)
        // lastActivity == 0 (unparsed) must never be reaped, even though 0 < cutoff.
        val unknown = TmuxSessionInfo("rk_unknown", created = now, lastActivity = 0L)
        val reaped = staleSessionsToReap(listOf(fresh, stale, unknown), now, ttlMs)
        assertEquals(listOf("rk_stale"), reaped.map { it.name })
    }

    @Test
    fun takeLastUtf8Bytes_keepsTailOnByteBoundary() {
        // ASCII: byte count == char count.
        assertEquals("cde", takeLastUtf8Bytes("abcde", 3))
        // fits entirely.
        assertEquals("abc", takeLastUtf8Bytes("abc", 10))
        assertEquals("", takeLastUtf8Bytes("abc", 0))
    }

    @Test
    fun takeLastUtf8Bytes_neverSplitsMultibyte() {
        // Each CJK char is 3 UTF-8 bytes. Budget 4 must keep only the last whole char (3 bytes),
        // never half of a 3-byte sequence.
        val s = "你好" // two 3-byte chars, 6 bytes total
        val out = takeLastUtf8Bytes(s, 4)
        assertEquals("好", out)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 4)
    }

    @Test
    fun takeFirstUtf8Bytes_neverSplitsMultibyte() {
        val s = "你好世" // three 3-byte chars, 9 bytes
        val out = takeFirstUtf8Bytes(s, 4)
        assertEquals("你", out) // only the first whole char fits in 4 bytes
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 4)
        assertEquals("abc", takeFirstUtf8Bytes("abcde", 3))
    }

    @Test
    fun takeLastUtf8Bytes_honorsSurrogatePairs() {
        // Each emoji is a surrogate PAIR (2 Java chars) but a single 4-byte UTF-8 sequence.
        // Regression: measuring per-char counted each surrogate half separately, ~2x over
        // budget, and could cut between the halves. Budget 4 must keep exactly one whole emoji.
        val s = "😀😁" // two emoji, 8 bytes, 4 chars
        val out = takeLastUtf8Bytes(s, 4)
        assertEquals("😁", out)
        assertEquals(4, out.toByteArray(Charsets.UTF_8).size)
        // Budget 5 still fits only one emoji (the second is 4 bytes, no room for a partial).
        assertEquals("😁", takeLastUtf8Bytes(s, 5))
        // Budget 7 still under 8 total: one whole emoji survives, never a split surrogate pair.
        assertEquals("😁", takeLastUtf8Bytes(s, 7))
    }

    @Test
    fun takeFirstUtf8Bytes_honorsSurrogatePairs() {
        val s = "😀😁" // two emoji, 8 bytes
        val out = takeFirstUtf8Bytes(s, 4)
        assertEquals("😀", out)
        assertEquals(4, out.toByteArray(Charsets.UTF_8).size)
        assertEquals("😀", takeFirstUtf8Bytes(s, 7))
        // Mixed: an ASCII prefix then an emoji. Budget 5 keeps "a" + one emoji (1 + 4 = 5).
        assertEquals("a😀", takeFirstUtf8Bytes("a😀😁", 5))
    }
}
