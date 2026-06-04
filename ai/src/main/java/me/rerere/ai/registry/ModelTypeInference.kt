package me.rerere.ai.registry

import me.rerere.ai.provider.ModelType

/**
 * Conservative, name-based heuristic for providers whose `/models` endpoint omits a model type
 * (e.g. OpenAI-compatible APIs, which only return an id). It only PROMOTES ids that obviously
 * name an embedding model to [ModelType.EMBEDDING]; everything else stays [ModelType.CHAT].
 *
 * Default-to-CHAT is the safe fallback: a chat model wrongly typed EMBEDDING would pollute the
 * RAG/knowledge-base picker, which is worse than the reverse.
 *
 * Providers that already return an authoritative type (Gemini, via
 * `supportedGenerationMethods`) must NOT route through this — it would be redundant and could
 * only regress a correct, non-name-based classification.
 */
fun guessModelType(modelId: String): ModelType =
    if (modelId.lowercase().contains("embed")) ModelType.EMBEDDING else ModelType.CHAT
