package me.rerere.rikkahub.ui.components.message.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the [ToolUIRegistry] dispatch contract (#197 slice 4): resolution is TOTAL — every tool name
 * maps to a renderer, an UNKNOWN one to the default fallback (so an unregistered/future tool renders
 * generically instead of crashing the chat UI), and a registered built-in to its own renderer. The
 * default renderer is the only one with an empty [ToolUIRenderer.toolName], so that is the observable
 * signature of "fell back".
 */
class ToolUIRegistryTest {

    @Test
    fun `an unknown tool resolves to the default fallback renderer`() {
        // Total fallback: never null, never throws — an unregistered tool gets the generic renderer
        // (empty toolName is the default renderer's signature).
        assertEquals("", ToolUIRegistry.resolve("__no_such_tool__").toolName)
        assertEquals("", ToolUIRegistry.resolve("").toolName)
    }

    @Test
    fun `a registered built-in resolves to its own renderer`() {
        assertEquals("memory_tool", ToolUIRegistry.resolve("memory_tool").toolName)
        assertEquals("search_web", ToolUIRegistry.resolve("search_web").toolName)
        assertEquals("scrape_web", ToolUIRegistry.resolve("scrape_web").toolName)
    }

    @Test
    fun `both the legacy task name and the new agent name resolve to TaskToolUI`() {
        // The spawn tool is advertised as `agent` but pre-rename transcripts and in-flight pending
        // calls carry `task`; the registry must alias the SAME renderer object under both keys so an
        // old `toolName="task"` step and a new `toolName="agent"` step render identically. Identity
        // (not just equal toolName) pins that it is the one TaskToolUI, not an unrelated renderer.
        assertSame(TaskToolUI, ToolUIRegistry.resolve("task"))
        assertSame(TaskToolUI, ToolUIRegistry.resolve("agent"))
    }
}
