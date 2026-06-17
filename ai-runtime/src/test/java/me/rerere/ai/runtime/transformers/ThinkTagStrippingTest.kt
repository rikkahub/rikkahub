package me.rerere.ai.runtime.transformers

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

/**
 * Pins the provider-agnostic `<think>` extraction extracted into `:ai-runtime` (issue #260). Covers
 * the core leading-preamble extraction behavior and finalization/streaming differences for
 * unclosed leading blocks (now no longer extracted in either mode).
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
    fun `only leading think blocks are extracted and stop at visible text`() {
        val msg = assistantText("<think>thought one</think>answer A<think>thought two</think>answer B")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(listOf("thought one"), reasonings(result).map { it.reasoning })
        assertEquals("answer A<think>thought two</think>answer B", text(result))
    }

    @Test
    fun `extracts consecutive closed leading think blocks and keeps trailing text`() {
        val msg = assistantText("<think>thought one</think><think>thought two</think>visible")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(listOf("thought one", "thought two"), reasonings(result).map { it.reasoning })
        assertEquals("visible", text(result))
    }

    @Test
    fun `single extraction keeps trailing visible text`() {
        val msg = assistantText("<think>X</think>Y")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(listOf("X"), reasonings(result).map { it.reasoning })
        assertEquals("Y", text(result))
    }

    @Test
    fun `empty leading think block is preserved as empty reasoning and removes wrapper tags`() {
        val msg = assistantText("<think></think>Y")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(listOf(""), reasonings(result).map { it.reasoning })
        assertEquals("Y", text(result))
    }

    @Test
    fun `unclosed leading think blocks are not extracted`() {
        val msg = assistantText("<think>still thinking")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(emptyList<String>(), reasonings(result).map { it.reasoning })
        assertEquals("<think>still thinking", text(result))
    }

    @Test
    fun `finalization keeps non-leading think text unchanged`() {
        val examples = listOf(
            "literal <think> tag",
            "prefix `<think>x</think>` suffix",
            "inline answer before `<think>x</think>` marker",
        )

        examples.forEach { message ->
            val result = stripThinkTags(listOf(assistantText(message)), now, zone).single()

            assertEquals(emptyList<String>(), reasonings(result).map { it.reasoning })
            assertEquals(message, text(result))
        }
    }

    @Test
    fun `finalization keeps random non-leading think text unchanged`() {
        runBlocking {
            checkAll(
                60,
                Arb.string(0..120),
            ) { randomSuffix ->
                val input = "answer says: $randomSuffix"
                val result = stripThinkTags(
                    listOf(assistantText(input)),
                    now,
                    zone,
                ).single()

                assertEquals(emptyList<String>(), reasonings(result).map { it.reasoning })
                assertEquals(input, text(result))
            }
        }
    }

    @Test
    fun `unclosed think stays visible in both streaming and finalization passes`() {
        val msg = assistantText("<think>still thinking")
        val streaming = stripThinkTags(listOf(msg), now, zone).single()
        val finalization = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(emptyList<String>(), reasonings(streaming).map { it.reasoning })
        assertEquals("<think>still thinking", text(streaming))

        assertEquals(emptyList<String>(), reasonings(finalization).map { it.reasoning })
        assertEquals("<think>still thinking", text(finalization))
    }

    @Test
    fun `runtime-style visualTransform then onGenerationFinish keeps unclosed leading think visible`() {
        val msg = assistantText("<think>still thinking")
        val afterVisual = stripThinkTags(listOf(msg), now, zone).single()
        val afterFinish = stripThinkTags(listOf(afterVisual), now, zone).single()

        assertEquals(emptyList<String>(), reasonings(afterFinish).map { it.reasoning })
        assertEquals("<think>still thinking", text(afterFinish))
    }

    @Test
    fun `onGenerationFinish stamps closed blocks as finished`() {
        val msg = assistantText("<think>done thinking</think>answer")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(now, reasonings(result).single().finishedAt)
    }

    @Test
    fun `stripThinkTags is idempotent for leading think preamble`() {
        val msg = assistantText("<think>X</think>Y")
        val firstPass = stripThinkTags(listOf(msg), now, zone).single()
        val secondPass = stripThinkTags(listOf(firstPass), now, zone).single()

        assertEquals(
            firstPass.parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
            secondPass.parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
        assertEquals(
            firstPass.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning },
            secondPass.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning },
        )
    }

    @Test
    fun `text without think tags is returned untouched`() {
        val msg = assistantText("plain answer")
        val result = stripThinkTags(listOf(msg), now, zone).single()

        assertEquals(emptyList<UIMessagePart.Reasoning>(), reasonings(result))
        assertEquals("plain answer", text(result))
    }

    @Test
    fun `non-assistant messages are not transformed`() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("<think>x</think>hi")))
        val result = stripThinkTags(listOf(user), now, zone).single()

        assertEquals(emptyList<UIMessagePart.Reasoning>(), reasonings(result))
        assertEquals("<think>x</think>hi", text(result))
    }
}
