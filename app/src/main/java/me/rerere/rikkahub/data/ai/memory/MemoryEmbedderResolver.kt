package me.rerere.rikkahub.data.ai.memory

import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import me.rerere.rikkahub.data.rag.embedding.KoogEmbedder

/**
 * Composition-root resolver that builds the memory embedding context from the CURRENT settings — the
 * single place that maps `Settings.memoryEmbeddingModelId` to a concrete [KoogEmbedder] + the
 * `baseUrl#modelId` embedding-space label (issue #210 §5 D3).
 *
 * Shared by both the write path ([me.rerere.rikkahub.data.repository.MemoryRepository], embed-on-write)
 * and the read path ([EmbeddingMemoryRecaller], embed-on-recall) so the model resolution and space
 * label are computed identically in one place (DRY). Resolved per call from `settingsProvider()` so a
 * model add/change/delete self-heals on the next write/recall, exactly like
 * [KnowledgeStoreFactory.buildStore] returning null on an unset/unresolvable model.
 *
 * Returns `null` when no embedding model is configured, or the configured model is missing/not backed
 * by an OpenAI-compatible provider (only those expose embeddings) — the callers then degrade to
 * recency rather than failing.
 */
class MemoryEmbedderResolver(
    private val providerManager: ProviderManager,
    private val settingsProvider: () -> Settings,
) {
    fun resolve(): EmbeddingMemoryRecaller.EmbeddingContext? {
        val settings = settingsProvider()
        val modelId = settings.memoryEmbeddingModelId ?: return null
        val model = settings.findModelById(modelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) as? ProviderSetting.OpenAI
            ?: return null
        val embedder = KoogEmbedder(
            providerManager = providerManager,
            providerSetting = providerSetting,
            model = model,
        )
        return EmbeddingMemoryRecaller.EmbeddingContext(
            embedder = embedder,
            embeddingSpace = KnowledgeStoreFactory.embeddingSpaceLabel(providerSetting.baseUrl, model.modelId),
        )
    }
}
