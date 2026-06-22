package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.MCP_TOOL_NAME_MAX_LEN
import me.rerere.rikkahub.service.buildMcpTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Equivalence + collision test for [buildMcpTools] — the single MCP-pool adapter used by BOTH the
 * main-agent pool ([me.rerere.rikkahub.data.ai.runtime.AppToolCatalog]) and the subagent pool, so the
 * naming invariant cannot drift between them (issue #244 DRY, issue #356 #2).
 *
 * Pins: readable model-facing name `mcp__<serverName>__<tool>`, description (null -> ""), needsApproval
 * passthrough, parameters() == inputSchema, an execute() that forwards exactly (serverId, RAW tool.name)
 * to callTool (the model-facing name is sanitized/deduped, the wire name is not), and collision-free
 * de-dup when two distinct servers expose the same tool name — the legacy `"mcp__" + name` bug.
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
    fun `maps readable name, description fallback, approval and schema`() {
        val serverId = Uuid.random()
        val names = mapOf(serverId to "context7")

        val tools = buildMcpTools(
            entries = listOf(
                serverId to mcpTool("lookup", "desc", needsApproval = true),
                serverId to mcpTool("resolve", null, needsApproval = false),
            ),
            serverName = { names[it].orEmpty() },
        ) { _, _, _ -> emptyList() }

        val lookup = tools.single { it.name.endsWith("__lookup") }
        assertEquals("mcp__context7__lookup", lookup.name)
        assertEquals("desc", lookup.description)
        assertEquals(true, lookup.needsApproval)
        assertSame(schema, lookup.parameters())

        val resolve = tools.single { it.name.endsWith("__resolve") }
        assertEquals("mcp__context7__resolve", resolve.name)
        // null description must collapse to empty string, exactly as the old inline `tool.description ?: ""`.
        assertEquals("", resolve.description)
        assertEquals(false, resolve.needsApproval)
    }

    @Test
    fun `execute forwards serverId and the RAW tool name to callTool`() = runBlocking {
        for (serverId in listOf(Uuid.random(), Uuid.random())) {
            var seenServerId: Uuid? = null
            var seenToolName: String? = null
            var seenArgs: JsonObject? = null
            val sentinel = listOf<UIMessagePart>(UIMessagePart.Text("ok"))

            val tools = buildMcpTools(
                entries = listOf(serverId to mcpTool("do_thing", "d", needsApproval = false)),
                serverName = { "srvname" },
            ) { sid, name, args ->
                seenServerId = sid
                seenToolName = name
                seenArgs = args
                sentinel
            }

            val args = buildJsonObject { put("q", "hello") }
            val result = tools.single().execute(args)

            assertEquals(serverId, seenServerId)
            // The wire name passed to callTool is the RAW tool name, not the sanitized model-facing one.
            assertEquals("do_thing", seenToolName)
            assertEquals(args, seenArgs)
            assertSame(sentinel, result)
        }
    }

    @Test
    fun `two distinct servers exposing the same tool name get distinct collision-free names`() {
        val a = Uuid.random()
        val b = Uuid.random()
        // Differently-named servers: both readable, distinct via the server segment.
        val named = buildMcpTools(
            entries = listOf(a to mcpTool("search", "", false), b to mcpTool("search", "", false)),
            serverName = { mapOf(a to "github", b to "context7")[it].orEmpty() },
        ) { _, _, _ -> emptyList() }
        assertEquals(setOf("mcp__github__search", "mcp__context7__search"), named.map { it.name }.toSet())

        // Pathological identically-named servers: the de-dup suffix keeps them unique (legacy collision).
        val collided = buildMcpTools(
            entries = listOf(a to mcpTool("search", "", false), b to mcpTool("search", "", false)),
            serverName = { "ctx" },
        ) { _, _, _ -> emptyList() }
        val names = collided.map { it.name }
        assertEquals("both tools must survive with distinct names", 2, names.toSet().size)
        assertTrue(names.contains("mcp__ctx__search"))
        assertTrue("the second gets a numeric de-dup suffix", names.any { it == "mcp__ctx__search_2" })
    }

    @Test
    fun `mass collision stays unique and within the provider name cap`() {
        val a = Uuid.random()
        // Pathological: 1001 entries that all reduce to the same MAX-LENGTH base name. A LONG raw tool
        // name forces the base to the reserved cap (`mcp__ctx__` + 50 chars = 60), so once the de-dup
        // suffix grows past `_999` to a 5-char `_1000` the pre-fix `base + suffix` would be 65 > 64.
        // The length-aware suffixing must truncate the base to keep every name <= 64 (issue #356 #2).
        // (A short base would make this test pass even on the buggy code — it must be cap-length.)
        val longTool = "x".repeat(100)
        val entries = (0..1000).map { a to mcpTool(longTool, "", false) }
        val names = buildMcpTools(entries, { "ctx" }) { _, _, _ -> emptyList() }.map { it.name }

        assertEquals("every collision must get a unique name", entries.size, names.toSet().size)
        assertTrue(
            "every de-duped name must stay within the provider cap (fails pre-fix at `_1000`)",
            names.all { it.length <= MCP_TOOL_NAME_MAX_LEN },
        )
    }

    @Test
    fun `de-dup suffix does not collide with a real tool already named like the suffix`() {
        val a = Uuid.random()
        // A real tool literally named `search_2` must not be clobbered by the suffix the second
        // `search` would otherwise take — the `taken` check forces it to `_3`.
        val entries = listOf(
            a to mcpTool("search", "", false),
            a to mcpTool("search_2", "", false),
            a to mcpTool("search", "", false),
        )
        val names = buildMcpTools(entries, { "ctx" }) { _, _, _ -> emptyList() }.map { it.name }

        assertEquals(3, names.toSet().size)
        assertTrue(names.contains("mcp__ctx__search"))
        assertTrue(names.contains("mcp__ctx__search_2"))
        assertTrue(names.contains("mcp__ctx__search_3"))
    }

    @Test
    fun `unnamed server falls back to a stable id segment, still distinct per server`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val tools = buildMcpTools(
            entries = listOf(a to mcpTool("search", "", false), b to mcpTool("search", "", false)),
            serverName = { "" },
        ) { _, _, _ -> emptyList() }
        val names = tools.map { it.name }
        assertEquals(2, names.toSet().size)
        assertTrue("unnamed servers use a srv<id> segment", names.all { it.startsWith("mcp__srv") })
        assertNotEquals(names[0], names[1])
    }
}
