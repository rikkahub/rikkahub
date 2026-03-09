package me.rerere.rikkahub.data.ai.tools.termux

import org.junit.Assert.assertEquals
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
}
