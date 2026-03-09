package me.rerere.rikkahub.data.ai.tools.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxResultTest {
    @Test
    fun `hasInternalError should ignore missing err code`() {
        val result = TermuxResult(errCode = null)

        assertFalse(result.hasInternalError())
    }

    @Test
    fun `hasInternalError should treat termux ok code as success`() {
        val result = TermuxResult(errCode = TERMUX_RESULT_OK)

        assertFalse(result.hasInternalError())
    }

    @Test
    fun `hasInternalError should flag non ok err code`() {
        val result = TermuxResult(errCode = 1)

        assertTrue(result.hasInternalError())
    }

    @Test
    fun `isSuccessful should reject non zero exit code`() {
        val result = TermuxResult(exitCode = 1)

        assertFalse(result.isSuccessful())
    }

    @Test
    fun `isSuccessful should reject timeout`() {
        val result = TermuxResult(timedOut = true)

        assertFalse(result.isSuccessful())
    }

    @Test
    fun `isSuccessful should accept clean completion`() {
        val result = TermuxResult(exitCode = 0)

        assertTrue(result.isSuccessful())
    }
}
