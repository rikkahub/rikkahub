package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the data-loss bug where multiple <think> blocks in one assistant text
 * part once again go through deterministic `stripThinkTags` parsing.
 */
class ThinkTagTransformerTest {

    private fun ctx() = TransformerContext(
        context = ContextWrapper(null),
        model = Model(),
        assistant = Assistant(),
        settings = Settings(),
    )

    private fun assistantText(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun reasonings(msg: UIMessage) =
        msg.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning }

    private fun texts(msg: UIMessage) =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `single think block extracts reasoning and strips text`() = runBlocking {
        val msg = assistantText("<think>first thought</think>visible answer")
        val result = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(msg)).single()

        assertEquals(listOf("first thought"), reasonings(result))
        assertEquals("visible answer", texts(result))
    }

    @Test
    fun `only leading think blocks before visible answer are extracted`() = runBlocking {
        val msg = assistantText(
            "<think>thought one</think>answer A<think>thought two</think>answer B"
        )
        val result = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(msg)).single()

        assertEquals(listOf("thought one"), reasonings(result))
        assertEquals("answer A<think>thought two</think>answer B", texts(result))
    }

    @Test
    fun `streaming only extracts leading think blocks before visible answer`() = runBlocking {
        val msg = assistantText(
            "<think>thought one</think>answer A<think>thought two</think>answer B"
        )
        val result = ThinkTagTransformer.visualTransform(ctx(), listOf(msg)).single()

        assertEquals(listOf("thought one"), reasonings(result))
        assertEquals("answer A<think>thought two</think>answer B", texts(result))
    }

    @Test
    fun `unclosed trailing think block stays visible during streaming`() = runBlocking {
        val msg = assistantText("<think>still thinking")
        val result = ThinkTagTransformer.visualTransform(ctx(), listOf(msg)).single()

        assertEquals(emptyList<String>(), reasonings(result))
        assertEquals("<think>still thinking", texts(result))
    }

    @Test
    fun `unclosed leading think block stays visible onGenerationFinish`() = runBlocking {
        val msg = assistantText("<think>still thinking")
        val result = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(msg)).single()

        assertEquals(emptyList<String>(), reasonings(result))
        assertEquals("<think>still thinking", texts(result))
    }

    @Test
    fun `visualTransform then onGenerationFinish keeps unclosed leading think visible`() = runBlocking {
        val msg = assistantText("<think>still thinking")
        val afterVisual = ThinkTagTransformer.visualTransform(ctx(), listOf(msg)).single()
        val afterFinish = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(afterVisual)).single()

        assertEquals(emptyList<String>(), reasonings(afterFinish))
        assertEquals("<think>still thinking", texts(afterFinish))
    }

    @Test
    fun `text without think tags is untouched`() = runBlocking {
        val msg = assistantText("plain answer with no tags")
        val result = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(msg)).single()

        assertEquals(emptyList<String>(), reasonings(result))
        assertEquals("plain answer with no tags", texts(result))
    }
}
