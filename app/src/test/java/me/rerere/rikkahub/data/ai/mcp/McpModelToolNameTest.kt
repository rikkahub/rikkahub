package me.rerere.rikkahub.data.ai.mcp

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.service.MCP_TOOL_NAME_MAX_LEN
import me.rerere.rikkahub.service.mcpModelToolName
import me.rerere.rikkahub.service.mcpServerSlug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Property + example suite for the READABLE MCP model-facing name functions (issue #356 #2). The name
 * is `mcp__<serverSlug>__<toolSlug>` — human-readable (the server NAME, e.g. `mcp__context7__lookup`,
 * not an opaque id), provider-safe (`[A-Za-z0-9_]`, ≤ [MCP_TOOL_NAME_MAX_LEN]), and the per-pool de-dup
 * that guarantees collision-freedom lives in `buildMcpTools` (see [AddMcpToolsTest]).
 */
class McpModelToolNameTest {

    private val safeName = Regex("^[A-Za-z0-9_]{1,$MCP_TOOL_NAME_MAX_LEN}\$")
    private val safeSlug = Regex("^[A-Za-z0-9_]+\$")

    @Test
    fun `readable name uses the server name and tool name verbatim`() {
        // The maintainer's example: the model should see the human server name, not an id.
        assertEquals("mcp__context7__lookup", mcpModelToolName("context7", "lookup"))
        assertEquals("mcp__github__search", mcpModelToolName("github", "search"))
    }

    @Test
    fun `server slug uses the human name and falls back to a short id only when unnamed`() {
        val id = Uuid.parse("12345678-1234-1234-1234-123456789abc")
        // A human name is sanitized to [A-Za-z0-9_], framing underscores trimmed.
        assertEquals("Context7_MCP", mcpServerSlug("Context7 MCP", id))
        assertEquals("my_server", mcpServerSlug("  my server  ", id))
        // Blank name → stable short id fallback (never an empty segment).
        assertEquals("srv12345678", mcpServerSlug("", id))
        assertEquals("srv12345678", mcpServerSlug("///", id))
    }

    @Test
    fun `name is provider-safe for arbitrary server and tool names`() {
        runBlocking {
            checkAll(500, Arb.string(0..40), Arb.string(0..120)) { serverName, rawTool ->
                val id = Uuid.random()
                val slug = mcpServerSlug(serverName, id)
                assertTrue("server slug `$slug` must be safe", safeSlug.matches(slug))
                val name = mcpModelToolName(slug, rawTool)
                assertTrue("`$name` (slug=`$slug`, tool=`$rawTool`) must match $safeName", safeName.matches(name))
                assertTrue("must keep the mcp__ prefix", name.startsWith("mcp__"))
                // Headroom for buildMcpTools' `_<n>` de-dup suffix must be left.
                assertTrue("base name must leave de-dup headroom", name.length <= MCP_TOOL_NAME_MAX_LEN - 4)
            }
        }
    }

    @Test
    fun `name is deterministic`() {
        runBlocking {
            checkAll(300, Arb.string(0..40), Arb.string(0..120)) { serverName, rawTool ->
                val id = Uuid.random()
                val slug = mcpServerSlug(serverName, id)
                assertEquals(mcpModelToolName(slug, rawTool), mcpModelToolName(slug, rawTool))
            }
        }
    }

    @Test
    fun `punctuation, over-length and empty tool names stay safe`() {
        assertTrue(safeName.matches(mcpModelToolName("github", "search/web-v2.0")))
        assertTrue(mcpModelToolName("github", "x".repeat(300)).length <= MCP_TOOL_NAME_MAX_LEN - 4)
        // Empty/all-punct tool name yields a valid, prefixed name (slug falls back to "tool").
        assertTrue(safeName.matches(mcpModelToolName("github", "")))
        assertEquals("mcp__github__tool", mcpModelToolName("github", "@@@"))
        // Different servers obviously produce different names for the same tool.
        assertNotEquals(mcpModelToolName("github", "search"), mcpModelToolName("context7", "search"))
    }
}
