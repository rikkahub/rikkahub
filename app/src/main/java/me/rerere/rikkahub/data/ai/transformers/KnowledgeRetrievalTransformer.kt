package me.rerere.rikkahub.data.ai.transformers

import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findKnowledgeBase
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.IOException

private const val TAG = "KnowledgeRetrievalTransformer"

/**
 * Input transformer that injects RAG context: embeds the last user message, runs a top-k cosine
 * similarity search against the assistant's attached knowledge base, and prepends the retrieved
 * chunks as a leading context [UIMessagePart.Text] on that message (same prepend idiom as
 * [DocumentAsPromptTransformer]).
 *
 * No-op gate: if the assistant has no knowledge base attached, returns the messages unchanged
 * without touching the embedder or store — zero overhead for the common case.
 */
object KnowledgeRetrievalTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val kbId = ctx.assistant.knowledgeBaseId ?: return messages

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages
        val queryText = messages[lastUserIndex].parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (queryText.isEmpty()) return messages

        val settings = get<SettingsStore>().settingsFlow.value
        val kb = settings.findKnowledgeBase(kbId) ?: return messages
        if (kb.documents.isEmpty()) return messages
        val store = get<KnowledgeStoreFactory>().buildStore(kb, settings) ?: return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在检索知识库..."
                val results = store.search(
                    request = SimilaritySearchRequest(queryText = queryText, limit = kb.topK),
                    namespace = kb.id.toString(),
                )
                if (results.isEmpty()) return@withContext messages

                val contextBlock = buildContextBlock(results.map { it.document.content })
                messages.mapIndexed { index, message ->
                    if (index == lastUserIndex) {
                        message.copy(
                            parts = buildList {
                                add(UIMessagePart.Text(contextBlock))
                                addAll(message.parts)
                            }
                        )
                    } else {
                        message
                    }
                }
            } catch (e: IOException) {
                // Only transient external-service failures are degraded to best-effort: a network
                // error embedding the query or reaching the provider must not block the chat. Other
                // exceptions (notably IllegalArgumentException from a dimension mismatch between the
                // query vector and stored vectors — i.e. a stale-embedding-model bug) are NOT caught
                // here; they propagate so the real defect surfaces instead of being silently masked.
                Log.w(TAG, "transform: knowledge retrieval skipped (network)", e)
                messages
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    private fun buildContextBlock(chunks: List<String>): String {
        val joined = chunks.joinToString("\n\n---\n\n")
        return """
            <knowledge_base_context>
            The following excerpts were retrieved from the user's attached knowledge base and may be
            relevant to the request. Use them when helpful; ignore them when not.

            $joined
            </knowledge_base_context>
        """.trimIndent()
    }
}
