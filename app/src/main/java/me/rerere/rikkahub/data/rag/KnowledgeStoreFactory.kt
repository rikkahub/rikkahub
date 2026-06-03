package me.rerere.rikkahub.data.rag

import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDAO
import me.rerere.rikkahub.data.rag.embedding.KoogEmbedder
import me.rerere.rikkahub.data.rag.store.RoomVectorStore

/**
 * Composition-root factory that wires the concrete RAG implementations behind Koog's abstractions:
 * resolves a knowledge base's embedding model to an OpenAI provider+model and builds a
 * [KoogEmbedder] + [RoomVectorStore] for it.
 *
 * Only OpenAI-compatible providers expose embeddings (`generateEmbedding`); a non-OpenAI selection
 * is reported explicitly via [buildStore] returning null rather than failing deep in the pipeline.
 */
class KnowledgeStoreFactory(
    private val providerManager: ProviderManager,
    private val knowledgeChunkDao: KnowledgeChunkDAO,
) {
    /**
     * @return a vector store for [kb], or null if its embedding model is unset or not backed by an
     *   OpenAI-compatible provider (embeddings unsupported).
     */
    fun buildStore(kb: KnowledgeBase, settings: Settings): RoomVectorStore? {
        val model = settings.findModelById(kb.embeddingModelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) as? ProviderSetting.OpenAI
            ?: return null
        val embedder = KoogEmbedder(
            providerManager = providerManager,
            providerSetting = providerSetting,
            model = model,
        )
        return RoomVectorStore(
            dao = knowledgeChunkDao,
            embedder = embedder,
            defaultNamespace = kb.id.toString(),
            embeddingModelLabel = model.modelId,
        )
    }
}
