package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Test
import sun.misc.Unsafe

class ChatMessageTransformersTest {

    private val templateTransformer = fakeTemplateTransformer()
    private val chatMessageTransformers = ChatMessageTransformers(templateTransformer = templateTransformer)

    @Test
    fun `chat message transformers uses expected input order`() {
        assertEquals(
            listOf(
                TimeReminderTransformer,
                PromptInjectionTransformer,
                PlaceholderTransformer,
                OcrTransformer,
                KnowledgeContextTransformer,
                templateTransformer,
            ),
            chatMessageTransformers.input,
        )
    }

    @Test
    fun `chat message transformers uses expected output order`() {
        assertEquals(
            listOf(ThinkTagTransformer, Base64ImageToLocalFileTransformer, RegexOutputTransformer),
            chatMessageTransformers.output,
        )
    }

    private fun fakeTemplateTransformer(): TemplateTransformer {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        return unsafe.allocateInstance(TemplateTransformer::class.java) as TemplateTransformer
    }
}
