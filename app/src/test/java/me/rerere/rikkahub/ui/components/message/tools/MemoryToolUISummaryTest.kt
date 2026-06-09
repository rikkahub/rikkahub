package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM guard for [MemoryToolUI.summaryContent], the single source of truth the inline
 * memory summary renders through.
 *
 * Regression: the upstream tool-step refactor (issue #197) split the old per-tool render block
 *   if (toolName == MEMORY && action in [create, edit]) { content("content")?.let { ... } }
 * into hasSummary()/Summary(). hasSummary() kept the `action in [create, edit]` guard but the
 * Summary() body dropped it and rendered whenever the "content" key was present. Because the
 * dispatcher (ChatMessageToolStep) calls Summary() unconditionally once the extra-content lambda
 * runs (which it also does for isDenied / images), Summary could render the memory text for a
 * non-create/edit action -- exactly what hasSummary() reports it will NOT. Routing both through
 * summaryContent() makes the action guard un-droppable; these tests pin that contract.
 */
class MemoryToolUISummaryTest {

    private fun context(action: String?, content: JsonElement?): ToolUIContext {
        val arguments: JsonElement = buildJsonObject {
            if (action != null) put("action", JsonPrimitive(action))
        }
        return ToolUIContext(
            tool = UIMessagePart.Tool(toolCallId = "id", toolName = "memory_tool", input = "{}"),
            arguments = arguments,
            content = content,
            loading = false,
        )
    }

    private fun contentWith(text: String?): JsonObject = buildJsonObject {
        if (text != null) put("content", JsonPrimitive(text))
    }

    @Test
    fun `create with content shows the memory text`() {
        assertEquals("hi", MemoryToolUI.summaryContent(context("create", contentWith("hi"))))
    }

    @Test
    fun `edit with content shows the memory text`() {
        assertEquals("hi", MemoryToolUI.summaryContent(context("edit", contentWith("hi"))))
    }

    // The exact divergence: a non-create/edit action that carries a "content" key must render
    // nothing. The unfixed Summary() rendered the text here; hasSummary() said it would not.
    @Test
    fun `delete with content shows nothing`() {
        assertNull(MemoryToolUI.summaryContent(context("delete", contentWith("hi"))))
    }

    @Test
    fun `unknown action with content shows nothing`() {
        assertNull(MemoryToolUI.summaryContent(context("search", contentWith("hi"))))
    }

    @Test
    fun `missing action with content shows nothing`() {
        assertNull(MemoryToolUI.summaryContent(context(null, contentWith("hi"))))
    }

    @Test
    fun `create without content shows nothing`() {
        assertNull(MemoryToolUI.summaryContent(context("create", contentWith(null))))
    }

    @Test
    fun `create with null content shows nothing`() {
        assertNull(MemoryToolUI.summaryContent(context("create", content = null)))
    }

    // The contract the refactor must keep in lockstep: hasSummary() is true exactly when
    // summaryContent() is non-null, so Summary() (which now early-returns via the same resolver)
    // can never render content hasSummary() denies.
    @Test
    fun `hasSummary agrees with summaryContent`() {
        val cases = listOf(
            context("create", contentWith("hi")),
            context("edit", contentWith("hi")),
            context("delete", contentWith("hi")),
            context("search", contentWith("hi")),
            context(null, contentWith("hi")),
            context("create", contentWith(null)),
            context("create", content = null),
        )
        cases.forEach { ctx ->
            assertEquals(
                MemoryToolUI.summaryContent(ctx) != null,
                MemoryToolUI.hasSummary(ctx),
            )
        }
    }
}
