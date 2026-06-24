package me.rerere.ai.provider

import android.content.ContextWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the explicit sealed-typed dispatch seam of [ProviderManager].
 *
 * The injected provider instances must be returned verbatim per sealed [ProviderSetting]
 * variant — no map indirection, no runtime mutation. Constructing the manager with
 * [ProviderInstances] of fakes lets each dispatch be asserted by identity (assertSame).
 *
 * ContextWrapper(null) is safe: the manager never touches the context when providers are
 * injected (the default factory is bypassed), so the null cacheDir is never dereferenced.
 */
class ProviderManagerTest {

    private class FakeOpenAIProvider : Provider<ProviderSetting.OpenAI> {
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")
        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = emptyFlow()
        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = emptyFlow()
    }

    private class FakeGoogleProvider : Provider<ProviderSetting.Google> {
        override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.Google,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")
        override suspend fun streamText(
            providerSetting: ProviderSetting.Google,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = emptyFlow()
        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = emptyFlow()
    }

    private class FakeClaudeProvider : Provider<ProviderSetting.Claude> {
        override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.Claude,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")
        override suspend fun streamText(
            providerSetting: ProviderSetting.Claude,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = emptyFlow()
        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = emptyFlow()
    }

    private class FakeChatGPTProvider : Provider<ProviderSetting.OpenAI> {
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")
        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = emptyFlow()
        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = emptyFlow()
    }

    private val fakeOpenAI = FakeOpenAIProvider()
    private val fakeGoogle = FakeGoogleProvider()
    private val fakeClaude = FakeClaudeProvider()
    private val fakeChatGPT = FakeChatGPTProvider()

    private fun manager() = ProviderManager(
        client = OkHttpClient(),
        context = ContextWrapper(null),
        providers = ProviderInstances(
            openAI = fakeOpenAI,
            google = fakeGoogle,
            claude = fakeClaude,
            chatGPT = fakeChatGPT,
        ),
    )

    @Test
    fun `getProviderByType dispatches OpenAI setting to injected OpenAI provider`() {
        assertSame(fakeOpenAI, manager().getProviderByType(ProviderSetting.OpenAI()))
    }

    @Test
    fun `getProviderByType dispatches Google setting to injected Google provider`() {
        assertSame(fakeGoogle, manager().getProviderByType(ProviderSetting.Google()))
    }

    @Test
    fun `getProviderByType dispatches Claude setting to injected Claude provider`() {
        assertSame(fakeClaude, manager().getProviderByType(ProviderSetting.Claude()))
    }

    @Test
    fun `getProviderByType dispatches ChatGPT setting to injected ChatGPT provider`() {
        assertSame(fakeChatGPT, manager().getProviderByType(ProviderSetting.OpenAI(mode = OpenAIMode.ChatGPT)))
    }

    @Test
    fun `getProvider resolves known names to injected providers`() {
        val m = manager()
        assertSame(fakeOpenAI, m.getProvider("openai"))
        assertSame(fakeGoogle, m.getProvider("google"))
        assertSame(fakeClaude, m.getProvider("claude"))
        assertSame(fakeChatGPT, m.getProvider("chatgpt"))
    }

    @Test
    fun `getProvider throws for unknown name`() {
        try {
            manager().getProvider("unknown")
            fail("expected IllegalArgumentException for unknown provider name")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
