package me.rerere.rikkahub.data.ai.agent

import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.prompt.AgentPermissionPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionPromptTest {
    @Test
    fun `chat mode with compatible policy injects nothing`() {
        val text = AgentPermissionPrompt.build(
            AgentMode.CHAT,
            PermissionPolicy.compatibleDefault(injectPromptForWorkspace = false),
        )
        assertTrue(text.isBlank())
    }

    @Test
    fun `plan mode instructs the assistant to plan before execution`() {
        val text = AgentPermissionPrompt.build(
            AgentMode.PLAN,
            PermissionPolicy.compatibleDefault(injectPromptForWorkspace = false),
        )
        assertTrue(text.contains("PLAN mode", ignoreCase = true))
        assertTrue(text.contains("first present a concise plan", ignoreCase = true))
        assertTrue(text.contains("execute necessary tools", ignoreCase = true))
    }

    @Test
    fun `agent mode injects execution notice`() {
        val text = AgentPermissionPrompt.build(
            AgentMode.AGENT,
            PermissionPolicy.compatibleDefault(injectPromptForWorkspace = false),
        )
        assertTrue(text.contains("AGENT mode", ignoreCase = true))
    }

    @Test
    fun `inject flag true uses policy full summary for plan`() {
        val text = AgentPermissionPrompt.build(
            AgentMode.PLAN,
            PermissionPolicy.compatibleDefault(injectPromptForWorkspace = true),
        )
        assertTrue(text.contains("<agent_permissions>"))
        assertTrue(text.contains("PLAN"))
        assertFalse(text.isBlank())
    }
}
