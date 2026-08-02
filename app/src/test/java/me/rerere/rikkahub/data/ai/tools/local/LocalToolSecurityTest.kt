package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.event.AppEventBus
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolSecurityTest {
    private val emptyArgs = buildJsonObject { }

    @Test
    fun `sensitive local tools always require approval`() {
        val context = ContextWrapper(null)
        val policy = PermissionPolicy.compatibleDefault()
        val clipboard = buildClipboardTool(context)
        val clipboardRead = buildJsonObject { put("action", "read") }
        val clipboardWrite = buildJsonObject {
            put("action", "write")
            put("text", "sensitive text")
        }

        assertTrue(policy.requiresApproval(clipboard, clipboardRead, AgentMode.CHAT))
        assertTrue(policy.requiresApproval(clipboard, clipboardWrite, AgentMode.CHAT))
        assertTrue(policy.requiresApproval(buildCalendarQueryTool(context), emptyArgs, AgentMode.CHAT))
        assertTrue(policy.requiresApproval(buildCalendarCreateTool(context), emptyArgs, AgentMode.CHAT))
        assertTrue(policy.requiresApproval(buildScreenTimeTool(context, AppEventBus()), emptyArgs, AgentMode.CHAT))
    }

    @Test
    fun `infinite JavaScript is rejected without evaluation`() = runBlocking {
        val result = buildJavascriptTool().execute(
            buildJsonObject {
                put("code", "while (true) {}")
            }
        )

        val response = (result.single() as UIMessagePart.Text).text
        assertTrue(response.contains("JAVASCRIPT_EXECUTION_DISABLED"))
        assertTrue(response.contains(JAVASCRIPT_EXECUTION_DISABLED_MESSAGE))
    }
}
