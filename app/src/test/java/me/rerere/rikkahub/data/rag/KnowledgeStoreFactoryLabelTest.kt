package me.rerere.rikkahub.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for issue #23: the embedding-space label must capture endpoint+model, not the model
 * name alone. The repro is editing a knowledge base's embedding model `baseUrl` (in provider
 * settings) to point at a different 1536-dim model while leaving the `modelId` string and model
 * Uuid unchanged. The model Uuid never changes, so the KB purge does not fire; the modelId string
 * never changes, so a modelId-only label does not change either; the dimension coincides, so cosine
 * does not throw. With a modelId-only label, the stale rows therefore pass the read-path filter and
 * are silently mis-ranked across two incomparable embedding spaces.
 *
 * Asserting on the pure [KnowledgeStoreFactory.embeddingSpaceLabel] keeps this a headless JVM test
 * (the full `buildStore` path needs a Context-backed `ProviderManager`, which CI cannot run).
 */
class KnowledgeStoreFactoryLabelTest {

    @Test
    fun `baseUrl-only change yields a different embedding-space label`() {
        val modelId = "text-embedding-3-small"
        val before = KnowledgeStoreFactory.embeddingSpaceLabel(
            baseUrl = "https://api.openai.com/v1",
            modelId = modelId,
        )
        // Same modelId string, same dimension, only the endpoint was repointed.
        val after = KnowledgeStoreFactory.embeddingSpaceLabel(
            baseUrl = "https://other-host.example/v1",
            modelId = modelId,
        )
        assertNotEquals(
            "a baseUrl edit must change the label so stale rows are excluded on read",
            before,
            after,
        )
    }

    @Test
    fun `identical endpoint and model yield a stable label`() {
        val a = KnowledgeStoreFactory.embeddingSpaceLabel("https://api.openai.com/v1", "m")
        val b = KnowledgeStoreFactory.embeddingSpaceLabel("https://api.openai.com/v1", "m")
        assertEquals(a, b)
    }
}
