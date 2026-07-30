package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.hooks.CompositeAgentHook
import me.rerere.rikkahub.data.ai.agent.hooks.LoggingAgentHook
import me.rerere.rikkahub.data.ai.agent.hooks.NoOpAgentHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHookTest {
    @Test
    fun `composite invokes hooks in order`() = runBlocking {
        val events = mutableListOf<String>()
        val a = object : me.rerere.rikkahub.data.ai.agent.hooks.AgentHook {
            override suspend fun beforeTool(tool: Tool, args: kotlinx.serialization.json.JsonElement) {
                events += "a-before-${tool.name}"
            }
            override suspend fun afterTool(
                tool: Tool,
                args: kotlinx.serialization.json.JsonElement,
                result: Result<List<UIMessagePart>>,
            ) {
                events += "a-after"
            }
        }
        val b = object : me.rerere.rikkahub.data.ai.agent.hooks.AgentHook {
            override suspend fun beforeTool(tool: Tool, args: kotlinx.serialization.json.JsonElement) {
                events += "b-before-${tool.name}"
            }
            override suspend fun afterTool(
                tool: Tool,
                args: kotlinx.serialization.json.JsonElement,
                result: Result<List<UIMessagePart>>,
            ) {
                events += "b-after"
            }
        }
        val hook = CompositeAgentHook(listOf(a, b))
        val tool = Tool(name = "t1", description = "d", execute = { listOf(UIMessagePart.Text("ok")) })
        val args = buildJsonObject { put("x", 1) }
        hook.beforeTool(tool, args)
        hook.afterTool(tool, args, Result.success(listOf(UIMessagePart.Text("ok"))))
        assertEquals(listOf("a-before-t1", "b-before-t1", "a-after", "b-after"), events)
    }

    @Test
    fun `logging hook records before and after via injectable logger`() = runBlocking {
        val lines = mutableListOf<String>()
        val hook = LoggingAgentHook(log = { _, msg -> lines += msg })
        val tool = Tool(name = "workspace_read_file", description = "r", execute = { emptyList() })
        val args = buildJsonObject { put("path", "/workspace/a") }
        hook.beforeTool(tool, args)
        hook.afterTool(tool, args, Result.success(emptyList()))
        assertTrue(lines.any { it.contains("beforeTool") && it.contains("workspace_read_file") })
        assertTrue(lines.any { it.contains("afterTool") && it.contains("success=true") })
    }

    @Test
    fun `logging hook omits tool failure details`() = runBlocking {
        val lines = mutableListOf<String>()
        val hook = LoggingAgentHook(log = { _, msg -> lines += msg })
        val tool = Tool(name = "workspace_write_file", description = "w", execute = { emptyList() })

        hook.afterTool(
            tool,
            buildJsonObject { put("path", "/private/file") },
            Result.failure(IllegalStateException("secret input and output")),
        )

        assertTrue(lines.single().contains("errorType=IllegalStateException"))
        assertTrue(lines.none { it.contains("secret input") || it.contains("/private/file") })
    }

    @Test
    fun `noop hook does nothing`() = runBlocking {
        val tool = Tool(name = "t", description = "d", execute = { emptyList() })
        NoOpAgentHook.beforeTool(tool, buildJsonObject { })
        NoOpAgentHook.afterTool(tool, buildJsonObject { }, Result.success(emptyList()))
    }
}
