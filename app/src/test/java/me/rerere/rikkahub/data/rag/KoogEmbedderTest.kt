package me.rerere.rikkahub.data.rag

import ai.koog.embeddings.base.Vector
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderInstances
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.rag.embedding.KoogEmbedder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.ai.provider.ImageGenerationParams
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Float -> Double mapping across the Provider <-> Koog boundary in [KoogEmbedder].
 * Uses a fake OpenAI provider returning a known float vector, registered into [ProviderManager].
 */
class KoogEmbedderTest {

    private val knownFloats = listOf(0.1f, -0.5f, 0.75f, 1.0f, 0.0f)

    private inner class FakeOpenAIProvider : Provider<ProviderSetting.OpenAI> {
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
        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting.OpenAI,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = EmbeddingGenerationResult(
            model = params.model.modelId,
            embeddings = listOf(knownFloats),
        )
        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("unused")
    }

    private inner class FakeGoogleProvider : Provider<ProviderSetting.Google> {
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
        ): ImageGenerationResult = error("unused")
    }

    private inner class FakeClaudeProvider : Provider<ProviderSetting.Claude> {
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
        ): ImageGenerationResult = error("unused")
    }

    @Test
    fun `float embedding maps to Vector of identical doubles and dimension`() = runBlocking {
        val manager = ProviderManager(
            OkHttpClient(),
            ContextWrapper(null),
            providers = ProviderInstances(
                openAI = FakeOpenAIProvider(),
                google = FakeGoogleProvider(),
                claude = FakeClaudeProvider(),
            ),
        )

        val embedder = KoogEmbedder(
            providerManager = manager,
            providerSetting = ProviderSetting.OpenAI(),
            model = Model(modelId = "text-embedding-3-small"),
        )

        val vector: Vector = embedder.embed("hello")

        assertEquals(knownFloats.size, vector.dimension)
        knownFloats.forEachIndexed { i, f ->
            assertEquals(f.toDouble(), vector.values[i], 1e-9)
        }
    }
}
