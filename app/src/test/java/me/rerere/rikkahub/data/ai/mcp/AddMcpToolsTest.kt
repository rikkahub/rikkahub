package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.mapMcpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Equivalence (metamorphic) test for [mapMcpTool] (issue #244, DRY extraction #1).
 *
 * The two MCP-adapter blocks in ChatService (the main-agent pool and the subagent pool) were
 * byte-identical apart from the source collection. The "mcp__" name prefix + the callTool wiring is
 * a real invariant that must not drift between the parent pool and the subagent pool, so the mapping
 * is extracted to a single pure [mapMcpTool] called from both sites.
 *
 * This test pins that the extracted mapping is element-wise identical to the old inline builder:
 * same name ("mcp__" + tool.name), description (null -> ""), needsApproval passthrough, parameters()
 * == inputSchema, and an execute() that forwards exactly (serverId, tool.name, args) to callTool.
 * It exercises two distinct serverIds to cover both the main (`assistant`) and subagent (`sub`) pools.
 */
class AddMcpToolsTest {

    private val schema = InputSchema.Obj(properties = JsonObject(emptyMap()))

    private fun mcpTool(name: String, description: String?, needsApproval: Boolean) =
        McpTool(
            name = name,
            description = description,
            inputSchema = schema,
            needsApproval = needsApproval,
        )

    @Test
    fun `maps name prefix, description fallback, approval and schema`() {
        val serverId = Uuid.random()

        val approved = mapMcpTool(serverId, mcpTool("search", "desc", needsApproval = true)) { _, _, _ -> emptyList() }
        assertEquals("mcp__search", approved.name)
        assertEquals("desc", approved.description)
        assertEquals(true, approved.needsApproval)
        assertSame(schema, approved.parameters())

        val nullDesc = mapMcpTool(serverId, mcpTool("fetch", null, needsApproval = false)) { _, _, _ -> emptyList() }
        assertEquals("mcp__fetch", nullDesc.name)
        // null description must collapse to empty string, exactly as the old inline `tool.description ?: ""`.
        assertEquals("", nullDesc.description)
        assertEquals(false, nullDesc.needsApproval)
    }

    @Test
    fun `execute forwards serverId, tool name and args to callTool`() = runBlocking {
        // Cover both pools: two distinct serverIds (main `assistant` vs subagent `sub` sources).
        for (serverId in listOf(Uuid.random(), Uuid.random())) {
            var seenServerId: Uuid? = null
            var seenToolName: String? = null
            var seenArgs: JsonObject? = null
            val sentinel = listOf<UIMessagePart>(UIMessagePart.Text("ok"))

            val tool = mapMcpTool(serverId, mcpTool("do_thing", "d", needsApproval = false)) { sid, name, args ->
                seenServerId = sid
                seenToolName = name
                seenArgs = args
                sentinel
            }

            val args = buildJsonObject { put("q", "hello") }
            val result = tool.execute(args)

            assertEquals(serverId, seenServerId)
            // The wire name passed to callTool is the RAW tool name, not the "mcp__"-prefixed one.
            assertEquals("do_thing", seenToolName)
            assertEquals(args, seenArgs)
            assertSame(sentinel, result)
        }
    }
}
