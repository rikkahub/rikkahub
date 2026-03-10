package me.rerere.rikkahub.data.ai.tools.termux

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxOutputFormatterTest {
    @Test
    fun `merge should trim trailing blank lines`() {
        val output = TermuxOutputFormatter.merge(
            stdout = "hello\n\n",
            stderr = "",
        )

        assertEquals("hello", output)
    }

    @Test
    fun `merge should join non-blank parts with a single newline`() {
        val output = TermuxOutputFormatter.merge(
            stdout = "hello\n",
            stderr = "warn\n\n",
            errMsg = "failed\n",
        )

        assertEquals("hello\nwarn\nfailed", output)
    }

    @Test
    fun `statusSummary should include timeout exit code and internal error`() {
        val output = TermuxOutputFormatter.statusSummary(
            TermuxResult(
                exitCode = 23,
                errCode = 7,
                errMsg = "boom",
                timedOut = true,
            )
        )

        assertEquals("Timed out\nExit code: 23\nErr code: 7\nboom", output)
    }

    @Test
    fun `normalizeTerminalOutput should strip ansi prompt noise`() {
        val raw = "Python 3.13.12\r\n" +
            "Type \"help\" for more information.\r\n" +
            "\u001B[?2004h\u001B[?1h\u001B=\u001B[?25l\u001B[1;35m>>> \u001B[0m\u001B[4D\u001B[?12l\u001B[?25h\u001B[4C"

        val output = TermuxOutputFormatter.normalizeTerminalOutput(raw)

        assertEquals(
            "Python 3.13.12\nType \"help\" for more information.\n>>> ",
            output
        )
    }

    @Test
    fun `normalizeTerminalOutput should collapse line redraws into readable text`() {
        val raw = "\u001B[1;35m>>> \u001B[0mp" +
            "\u001B[5D\u001B[1;35m>>> \u001B[0mpr" +
            "\u001B[6D\u001B[1;35m>>> \u001B[0mprint('x')\n\r" +
            "x\r\n"

        val output = TermuxOutputFormatter.normalizeTerminalOutput(raw)

        assertEquals(">>> print('x')\nx\n", output)
    }
}
