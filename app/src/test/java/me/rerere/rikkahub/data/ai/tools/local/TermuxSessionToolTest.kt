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
}
