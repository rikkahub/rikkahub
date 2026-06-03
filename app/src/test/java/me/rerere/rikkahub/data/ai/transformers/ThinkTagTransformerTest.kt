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
 * part collapsed into a single Reasoning part: `replace(REGEX, "")` strips every block but
 * `find()` only recovers the FIRST block's reasoning, silently dropping the rest. The dropped
 * reasoning is then persisted by onGenerationFinish, making the loss permanent.
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
    fun `multiple think blocks each become an ordered reasoning part`() = runBlocking {
        val msg = assistantText(
            "<think>thought one</think>answer A<think>thought two</think>answer B"
        )
        val result = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(msg)).single()

        // Bug: only "thought one" survived; "thought two" was stripped and dropped.
        assertEquals(listOf("thought one", "thought two"), reasonings(result))
        assertEquals("answer Aanswer B", texts(result))
    }

    @Test
    fun `multiple think blocks survive visualTransform during streaming`() = runBlocking {
        val msg = assistantText(
            "<think>thought one</think>answer A<think>thought two</think>answer B"
        )
        val result = ThinkTagTransformer.visualTransform(ctx(), listOf(msg)).single()

        assertEquals(listOf("thought one", "thought two"), reasonings(result))
        assertEquals("answer Aanswer B", texts(result))
    }

    @Test
    fun `unclosed trailing think block is captured during streaming`() = runBlocking {
        val msg = assistantText("<think>still thinking")
        val result = ThinkTagTransformer.visualTransform(ctx(), listOf(msg)).single()

        assertEquals(listOf("still thinking"), reasonings(result))
        assertEquals("", texts(result))
    }

    @Test
    fun `text without think tags is untouched`() = runBlocking {
        val msg = assistantText("plain answer with no tags")
        val result = ThinkTagTransformer.onGenerationFinish(ctx(), listOf(msg)).single()

        assertEquals(emptyList<String>(), reasonings(result))
        assertEquals("plain answer with no tags", texts(result))
    }
}
