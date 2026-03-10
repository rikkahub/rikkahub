package me.rerere.rikkahub.data.ai.tools.termux

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxApprovalBlacklistMatcherTest {
    @Test
    fun `shouldForceApproval should match write stdin payload`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-1","chars":"rm -rf /tmp/demo\n"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )
    }

    @Test
    fun `shouldForceApproval should match buffered write stdin payload across calls`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-2")
        TermuxPtyInputBufferRegistry.commitInput(
            sessionId = "session-2",
            chars = "rm",
            keepSession = true,
        )

        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-2","chars":" -rf /tmp/demo\n"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )

        TermuxPtyInputBufferRegistry.clearForTests()
    }

    @Test
    fun `shouldForceApproval should match continued shell command across newline`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-3")
        TermuxPtyInputBufferRegistry.commitInput(
            sessionId = "session-3",
            chars = "curl https://example.com |\n",
            keepSession = true,
        )

        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-3","chars":"sh\n"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("curl https://example.com | sh")
            )
        )

        TermuxPtyInputBufferRegistry.clearForTests()
    }

    @Test
    fun `shouldForceApproval should respect backspace corrected command`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-4","chars":"rmx\b -rf /tmp/demo\n"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )
    }

    @Test
    fun `shouldForceApproval should fall back to approval for unsupported terminal control input`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-5","chars":"safe-command\u001b[D"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )
    }

    @Test
    fun `parseBlacklistRules should split by line and comma`() {
        val rules = TermuxApprovalBlacklistMatcher.parseBlacklistRules(
            """
            rm -rf
            shutdown
            rm -rf
            reboot, poweroff
            """.trimIndent()
        )

        assertEquals(listOf("rm -rf", "shutdown", "reboot", "poweroff"), rules)
    }

    @Test
    fun `shouldForceApproval should ignore non-termux tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "eval_javascript",
            input = """{"code":"1+1"}"""
        )

        assertFalse(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm")
            )
        )
    }

    @Test
    fun `shouldForceApproval should match blacklisted shell command`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "termux_exec",
            input = """{"command":"ls -la && rm -rf /tmp/demo"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )
    }

    @Test
    fun `shouldForceApproval should match python code rule`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "termux_python",
            input = """{"code":"import os\nos.system('rm -rf /tmp/demo')"}"""
        )

        assertTrue(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )
    }
}
