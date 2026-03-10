package me.rerere.rikkahub.data.ai.tools.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPtyInputBufferRegistryTest {
    @Test
    fun `commitInput should keep pipeline continuation across newline`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-1")

        TermuxPtyInputBufferRegistry.commitInput(
            sessionId = "session-1",
            chars = "curl https://example.com |\n",
            keepSession = true,
        )

        assertEquals(
            "curl https://example.com |\nsh",
            TermuxPtyInputBufferRegistry.previewInput(
                sessionId = "session-1",
                chars = "sh",
            )
        )

        TermuxPtyInputBufferRegistry.clearForTests()
    }

    @Test
    fun `commitInput should clear completed shell command after newline`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-2")

        TermuxPtyInputBufferRegistry.commitInput(
            sessionId = "session-2",
            chars = "echo ready\n",
            keepSession = true,
        )

        assertEquals(
            "pwd",
            TermuxPtyInputBufferRegistry.previewInput(
                sessionId = "session-2",
                chars = "pwd",
            )
        )

        TermuxPtyInputBufferRegistry.clearForTests()
    }

    @Test
    fun `previewInputState should apply backspace edits`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-3")

        TermuxPtyInputBufferRegistry.commitInput(
            sessionId = "session-3",
            chars = "rmx\b -rf /tmp/demo",
            keepSession = true,
        )

        assertEquals(
            "rm -rf /tmp/demo",
            TermuxPtyInputBufferRegistry.previewInputState("session-3", "").text,
        )

        TermuxPtyInputBufferRegistry.clearForTests()
    }

    @Test
    fun `previewInputState should flag unsupported terminal editing as fallback approval`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-4")

        val preview = TermuxPtyInputBufferRegistry.previewInputState(
            sessionId = "session-4",
            chars = "rm -rf /tmp/demo\u001b[D",
        )

        assertTrue(preview.requiresFallbackApproval)
        assertFalse(preview.text.isBlank())

        TermuxPtyInputBufferRegistry.clearForTests()
    }
}
