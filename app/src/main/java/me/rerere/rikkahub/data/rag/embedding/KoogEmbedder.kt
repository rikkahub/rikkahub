package me.rerere.rikkahub.data.rag.embedding

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting

/**
 * Koog [Embedder] backed by the app's existing [me.rerere.ai.provider.providers.OpenAIProvider]
 * embedding endpoint. Reuses `generateEmbedding`; embedding logic is NOT duplicated here.
 *
 * The provider/model are resolved at the composition root (see
 * [me.rerere.rikkahub.data.rag.KnowledgeStoreFactory]) from the knowledge base's `embeddingModelId`,
 * so no API key is hard-coded. Only OpenAI-compatible providers support embeddings; the factory
 * restricts selection and the UI's model picker is scoped to [me.rerere.ai.provider.ModelType.EMBEDDING].
 *
 * Koog's [Vector] stores `List<Double>`, while the provider returns `List<Float>`; the boundary maps
 * explicitly in both directions.
 */
class KoogEmbedder(
    private val providerManager: ProviderManager,
    private val providerSetting: ProviderSetting.OpenAI,
    private val model: Model,
) : Embedder {

    override suspend fun embed(text: String): Vector {
        val provider = providerManager.getProviderByType(providerSetting)
        val result = provider.generateEmbedding(
            providerSetting = providerSetting,
            params = EmbeddingGenerationParams(
                model = model,
                input = listOf(text),
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val floats = result.embeddings.firstOrNull()
            ?: error("Embedding provider returned no vector for input")
        return Vector(floats.map { it.toDouble() })
    }

    override fun diff(embedding1: Vector, embedding2: Vector): Double =
        1.0 - embedding1.cosineSimilarity(embedding2)
}
