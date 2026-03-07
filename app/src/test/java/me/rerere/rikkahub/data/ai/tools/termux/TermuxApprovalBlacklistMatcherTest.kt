package me.rerere.rikkahub.data.ai.tools.termux

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxApprovalBlacklistMatcherTest {
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
