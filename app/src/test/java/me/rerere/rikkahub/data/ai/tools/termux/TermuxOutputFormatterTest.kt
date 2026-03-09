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
}
