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
    fun `shouldForceApproval should not stitch write stdin payload across calls`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-4","chars":" -rf /tmp/demo\n"}"""
        )

        assertFalse(
            TermuxApprovalBlacklistMatcher.shouldForceApproval(
                tool = tool,
                blacklistRules = listOf("rm -rf")
            )
        )
    }

    @Test
    fun `shouldForceApproval should ignore empty polling payload`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "write_stdin",
            input = """{"session_id":"session-5","chars":""}"""
        )

        assertFalse(
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
