package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class FrozenTransformerTest {
    @Test
    fun `message template comes from the frozen assistant`() = runBlocking {
        val assistant = Assistant(messageTemplate = "frozen: {{ message }}")
        val transformer = TemplateTransformer()

        val result = transformer.transform(
            context(assistant = assistant),
            listOf(UIMessage.user("hello")),
        )

        assertEquals("frozen: hello", (result.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `included assistant template comes from frozen settings`() = runBlocking {
        val included = Assistant(messageTemplate = "included: {{ message }}")
        val assistant = Assistant(messageTemplate = "{% include '${included.id}' %}")
        val transformer = TemplateTransformer()

        val result = transformer.transform(
            context(
                assistant = assistant,
                settings = Settings(assistants = listOf(assistant, included)),
            ),
            listOf(UIMessage.user("hello")),
        )

        assertEquals("included: hello", (result.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `nickname placeholder comes from frozen settings`() = runBlocking {
        val result = PlaceholderTransformer.transform(
            context(settings = Settings(displaySetting = DisplaySetting(userNickname = "frozen-user"))),
            listOf(UIMessage.user("Hello, {{nickname}}")),
        )

        assertEquals("Hello, frozen-user", (result.single().parts.single() as UIMessagePart.Text).text)
    }

    private fun context(
        assistant: Assistant = Assistant(),
        settings: Settings = Settings(),
    ) = TransformerContext(
        context = mock(Context::class.java),
        model = Model(),
        assistant = assistant,
        settings = settings,
    )
}
