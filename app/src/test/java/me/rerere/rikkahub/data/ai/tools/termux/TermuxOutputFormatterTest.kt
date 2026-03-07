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
}
