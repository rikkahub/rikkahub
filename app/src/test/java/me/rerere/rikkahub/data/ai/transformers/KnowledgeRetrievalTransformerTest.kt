package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.uuid.Uuid

class KnowledgeRetrievalTransformerTest {

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    private fun ctx(assistant: Assistant) = TransformerContext(
        context = ContextWrapper(null),
        model = Model(),
        assistant = assistant,
        settings = Settings(),
    )

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `no knowledge base attached is a zero-overhead no-op`() = runBlocking {
        // Register throwing fakes: if the transformer touches the store/settings, the test fails.
        startKoin {
            modules(
                module {
                    single<SettingsStore> { error("SettingsStore must not be accessed when no KB attached") }
                    single { error("KnowledgeStoreFactory must not be accessed when no KB attached") as KnowledgeStoreFactory }
                }
            )
        }

        val assistant = Assistant(knowledgeBaseId = null)
        val messages = listOf(userMessage("hello"))

        val result = KnowledgeRetrievalTransformer.transform(ctx(assistant), messages)

        // Same list instance returned, untouched.
        assertSame(messages, result)
    }

    @Test
    fun `no user message is a no-op even with KB attached`() = runBlocking {
        startKoin {
            modules(
                module {
                    single<SettingsStore> { error("must not be accessed") }
                    single { error("must not be accessed") as KnowledgeStoreFactory }
                }
            )
        }

        val assistant = Assistant(knowledgeBaseId = Uuid.random())
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi")))
        )

        val result = KnowledgeRetrievalTransformer.transform(ctx(assistant), messages)
        assertSame(messages, result)
    }
}
