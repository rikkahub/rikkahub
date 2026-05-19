package me.rerere.locallm.litert

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject

class LiteRtToolBridgeRegistryTest {

    @After
    fun teardown() {
        LiteRtToolBridgeRegistry.clear()
    }

    private fun stubTool(name: String): Tool = Tool(
        name = name,
        description = "stub",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    @Test
    fun `lookup returns null when no tools are registered`() {
        assertNull(LiteRtToolBridgeRegistry.lookup("anything"))
    }

    @Test
    fun `setForRequest replaces prior registration`() {
        LiteRtToolBridgeRegistry.setForRequest(listOf(stubTool("a"), stubTool("b")))
        assertNotNull(LiteRtToolBridgeRegistry.lookup("a"))
        assertNotNull(LiteRtToolBridgeRegistry.lookup("b"))
        LiteRtToolBridgeRegistry.setForRequest(listOf(stubTool("c")))
        assertNull(LiteRtToolBridgeRegistry.lookup("a"))
        assertNull(LiteRtToolBridgeRegistry.lookup("b"))
        assertNotNull(LiteRtToolBridgeRegistry.lookup("c"))
    }

    @Test
    fun `clear empties the registry`() {
        LiteRtToolBridgeRegistry.setForRequest(listOf(stubTool("x")))
        LiteRtToolBridgeRegistry.clear()
        assertNull(LiteRtToolBridgeRegistry.lookup("x"))
    }

    @Test
    fun `currentToolNames reflects the latest setForRequest`() {
        LiteRtToolBridgeRegistry.setForRequest(listOf(stubTool("a"), stubTool("b")))
        assertEquals(setOf("a", "b"), LiteRtToolBridgeRegistry.currentToolNames())
        LiteRtToolBridgeRegistry.setForRequest(emptyList())
        assertEquals(emptySet<String>(), LiteRtToolBridgeRegistry.currentToolNames())
    }
}
