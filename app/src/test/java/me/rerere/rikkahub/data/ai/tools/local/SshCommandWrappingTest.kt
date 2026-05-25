package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure shell-command wrapping helpers used by the SSH and Termux tools.
 * These cover the escaping logic that is easy to get wrong; the actual channel/stdin behaviour
 * needs a live SSH server and is verified on-device.
 */
class SshCommandWrappingTest {

    @Test
    fun `shellSingleQuote wraps a plain string in single quotes`() {
        assertEquals("'echo hi'", shellSingleQuote("echo hi"))
    }

    @Test
    fun `shellSingleQuote wraps an empty string`() {
        assertEquals("''", shellSingleQuote(""))
    }

    @Test
    fun `shellSingleQuote escapes embedded single quotes`() {
        // close-quote, escaped quote, reopen-quote: a'b -> 'a'\''b'
        assertEquals("'a'\\''b'", shellSingleQuote("a'b"))
    }

    @Test
    fun `shellSingleQuote escapes every embedded single quote`() {
        assertEquals("''\\''x'\\'''", shellSingleQuote("'x'"))
    }

    @Test
    fun `wrapDetachedCommand redirects all streams, backgrounds, and echoes the pid`() {
        assertEquals(
            "nohup sh -c 'echo hi' >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\"",
            wrapDetachedCommand("echo hi"),
        )
    }

    @Test
    fun `wrapDetachedCommand keeps a compound command intact inside the single-quoted body`() {
        val wrapped = wrapDetachedCommand("rm -rf /tmp/x; python3 -m http.server 9999")
        assertTrue(wrapped.startsWith("nohup sh -c 'rm -rf /tmp/x; python3 -m http.server 9999' "))
        assertTrue(wrapped.contains(">/dev/null 2>&1 </dev/null &"))
    }

    @Test
    fun `wrapDetachedCommand escapes a command that itself contains single quotes`() {
        assertEquals(
            "nohup sh -c 'echo '\\''a'\\''' >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\"",
            wrapDetachedCommand("echo 'a'"),
        )
    }
}
