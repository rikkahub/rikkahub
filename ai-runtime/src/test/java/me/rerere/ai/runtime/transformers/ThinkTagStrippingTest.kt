package me.rerere.ai.runtime.transformers

import kotlinx.datetime.TimeZone
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Instant

/**
 * Pins the provider-agnostic `<think>` extraction extracted into `:ai-runtime` (issue #260). Covers
 * the multi-block data-loss regression (each block must become its own ordered Reasoning part) AND
 * the clock-stamped `finishedAt` semantics that the app-side adapter test cannot pin (wall clock).
 */
class ThinkTagStrippingTest {

    private val now = Instant.parse("2026-01-02T03:04:05Z")
    private val zone = TimeZone.UTC

    private fun assistantText(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun reasonings(msg: UIMessage) =
        msg.parts.filterIsInstance<UIMessagePart.Reasoning>()

    private fun text(msg: UIMessage) =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `each think block becomes an ordered reasoning part and tags are stripped`() {
        val msg = assistantText("<think>thought one</think>answer A<think>thought two</think>answer B")
        val result = stripThinkTags(listOf(msg), now, zone, finishUnclosed = true).single()

        assertEquals(listOf("thought one", "thought two"), reasonings(result).map { it.reasoning })
        assertEquals("answer Aanswer B", text(result))
    }

    @Test
    fun `onGenerationFinish stamps every block finishedAt with the injected clock`() {
        val msg = assistantText("<think>done thinking</think>answer")
        val result = stripThinkTags(listOf(msg), now, zone, finishUnclosed = true).single()

        assertEquals(now, reasonings(result).single().finishedAt)
    }

    @Test
    fun `streaming leaves an unclosed trailing block unfinished but stamps closed blocks`() {
        val msg = assistantText("<think>closed</think>visible<think>still going")
        val parts = reasonings(stripThinkTags(listOf(msg), now, zone, finishUnclosed = false).single())

        assertEquals(listOf("closed", "still going"), parts.map { it.reasoning })
        // closed block -> finished at the injected clock; the in-flight block stays null.
        assertEquals(now, parts[0].finishedAt)
        assertNull(parts[1].finishedAt)
    }

    @Test
    fun `text without think tags is returned untouched`() {
        val msg = assistantText("plain answer")
        val result = stripThinkTags(listOf(msg), now, zone, finishUnclosed = true).single()

        assertEquals(emptyList<UIMessagePart.Reasoning>(), reasonings(result))
        assertEquals("plain answer", text(result))
    }

    @Test
    fun `non-assistant messages are not transformed`() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("<think>x</think>hi")))
        val result = stripThinkTags(listOf(user), now, zone, finishUnclosed = true).single()

        assertEquals(emptyList<UIMessagePart.Reasoning>(), reasonings(result))
        assertEquals("<think>x</think>hi", text(result))
    }
}
