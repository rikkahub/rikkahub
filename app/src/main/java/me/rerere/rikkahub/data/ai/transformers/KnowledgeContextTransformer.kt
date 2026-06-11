package me.rerere.rikkahub.data.ai.transformers

import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.estimateTokens
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.ai.runtime.knowledge.KnowledgeBudget
import me.rerere.ai.runtime.knowledge.KnowledgeContextAssembler
import me.rerere.ai.runtime.knowledge.KnowledgeContextBlock
import me.rerere.ai.runtime.knowledge.KnowledgeContextRenderer
import me.rerere.ai.runtime.knowledge.KnowledgeScope
import me.rerere.ai.runtime.knowledge.KnowledgeSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findKnowledgeBase
import me.rerere.rikkahub.data.document.DocumentExtractionResult
import me.rerere.rikkahub.data.document.DocumentPromptRenderer
import me.rerere.rikkahub.data.document.DocumentSource
import me.rerere.rikkahub.data.document.DocumentTextExtractor
import me.rerere.rikkahub.data.rag.KnowledgeBase
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.IOException

private const val TAG = "KnowledgeContextTransformer"

/**
 * The single message-surface assembly point for injected knowledge (issue #141 Phase 1, design D2
 * option B). REPLACES both [DocumentAsPromptTransformer] and `KnowledgeRetrievalTransformer`: each
 * used to prepend its content onto the last user message through its own ad-hoc idiom, with no shared
 * budget, so Attachment grew unbounded. Here the two emit candidate [KnowledgeContextBlock]s, a single
 * shared token budget selects the set that fits, and the renderer labels each block by source.
 *
 * Behavior deltas vs the two it replaces (both CAP injection, never expand — see PR body):
 *  - Attachment blocks are emitted from the LAST USER message's documents and rendered onto that
 *    message (design: "render onto last user msg"), vs the old transformer's map over every message.
 *  - Attachment and RAG now pass through the shared budget, so on a tiny budget some previously
 *    always-injected content may be dropped — the intended invariant-5 fix. With a generous budget the
 *    output equals today's (PBT P7).
 *
 * Preserved exactly: #102 typed-result rendering (a parse failure is never embedded as `<content>`),
 * #189 scanned-PDF -> [DocumentExtractionResult.Empty] rendered as a "no extractable text" note (never
 * content), #20 attachment ordering, #22 RAG relevance floor, and the IOException-only RAG degrade
 * with IllegalArgumentException (dimension mismatch) still propagating.
 */
object KnowledgeContextTransformer : InputMessageTransformer, KoinComponent {

    // Cross-source selection precedence on the message surface (issue #141 Q2): an attachment the user
    // just sent outranks retrieved RAG excerpts. Equal priorities break by KnowledgeSource ordinal.
    private const val ATTACHMENT_PRIORITY = 200
    private const val RAG_PRIORITY = 100

    // Shared with RAG ingestion (issue #102): one resolver/extractor, so chat and RAG route formats
    // identically and a parse failure can never be embedded into the prompt as document content.
    private val extractor = DocumentTextExtractor()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages

        val systemPromptTokens = estimateTokens(
            messages.firstOrNull { it.role == MessageRole.SYSTEM }?.parts ?: emptyList()
        )
        val budget = KnowledgeBudget.of(ctx.model, systemPromptTokens)
        // Nothing can be selected on a zero budget (the system prompt already fills the knowledge
        // slice): short-circuit before the attachment disk reads and the RAG retrieval IO/network so a
        // saturated turn does no wasted work (cross-model gate nit).
        if (budget <= 0) return messages

        // Attachment extraction is blocking IO (#189 suspend seam, absorbed here); the RAG branch
        // already hops to IO internally. Do the disk reads on IO too.
        val attachmentBlocks = withContext(Dispatchers.IO) {
            buildAttachmentBlocks(messages[lastUserIndex].parts, ::readDocumentContent)
        }
        val ragBlocks = retrieveRagBlocks(ctx, messages, lastUserIndex)

        val selected = KnowledgeContextAssembler.assemble(attachmentBlocks + ragBlocks, budget)
        if (selected.isEmpty()) return messages

        val leadingParts = selected.map { UIMessagePart.Text(KnowledgeContextRenderer.render(it)) }
        return messages.mapIndexed { index, message ->
            if (index == lastUserIndex) {
                message.copy(
                    parts = buildList {
                        addAll(leadingParts)
                        addAll(message.parts)
                    }
                )
            } else {
                message
            }
        }
    }

    /**
     * Emits one ATTACHMENT candidate per [UIMessagePart.Document], preserving the original attachment
     * order (issue #20) — documents A B C produce blocks A B C, not C B A. The rendered content is the
     * typed [DocumentPromptRenderer] output, so only [DocumentExtractionResult.Success] becomes
     * `<content>`; a parse failure / unsupported / Empty (scanned PDF, #189) is a metadata-only note,
     * never error text masquerading as document content (issue #102).
     *
     * Pure over a result-producing reader lambda so it is JVM unit-testable without Android URI/disk IO.
     */
    fun buildAttachmentBlocks(
        parts: List<UIMessagePart>,
        readContent: (UIMessagePart.Document) -> DocumentExtractionResult,
    ): List<KnowledgeContextBlock> =
        parts.filterIsInstance<UIMessagePart.Document>().map { document ->
            val content = DocumentPromptRenderer.render(document.fileName, readContent(document))
            val block = KnowledgeContextBlock(
                source = KnowledgeSource.ATTACHMENT,
                scope = KnowledgeScope.MESSAGE,
                title = document.fileName,
                content = content,
                priority = ATTACHMENT_PRIORITY,
                estimatedTokens = 0,
            )
            // Estimate over the RENDERED block (matching ragBlock), so the <attachment> wrapper the
            // renderer adds is counted against the budget — otherwise attachments under-count their
            // injected cost and the assembler can marginally overshoot the budget (cross-model gate).
            block.copy(
                estimatedTokens = estimateTokens(
                    listOf(UIMessagePart.Text(KnowledgeContextRenderer.render(block)))
                )
            )
        }

    /**
     * Builds the single RAG candidate from the already-joined retrieved chunk text. [estimatedTokens]
     * is measured over the RENDERED `<knowledge_base_context>` block (what is actually injected), so
     * the wrapper's tokens are counted against the budget. Pure, so it is unit-testable.
     */
    fun ragBlock(joinedChunks: String): KnowledgeContextBlock {
        val block = KnowledgeContextBlock(
            source = KnowledgeSource.RAG,
            scope = KnowledgeScope.MESSAGE,
            title = null,
            content = joinedChunks,
            priority = RAG_PRIORITY,
            estimatedTokens = 0,
        )
        return block.copy(
            estimatedTokens = estimateTokens(
                listOf(UIMessagePart.Text(KnowledgeContextRenderer.render(block)))
            )
        )
    }

    /**
     * Builds the similarity-search request for a retrieval turn. Always attaches
     * [KnowledgeBase.DEFAULT_MIN_SCORE] as the relevance floor: without it the store returns the
     * top-k nearest chunks regardless of how unrelated they are, so every turn pays context-window
     * tokens for noise (issue #22). Extracted as a pure function so the floor invariant is unit
     * testable on the JVM without standing up the store/settings dependencies.
     */
    fun buildRetrievalRequest(queryText: String, kb: KnowledgeBase): SimilaritySearchRequest =
        SimilaritySearchRequest(
            queryText = queryText,
            limit = kb.topK,
            minScore = KnowledgeBase.DEFAULT_MIN_SCORE,
        )

    private suspend fun retrieveRagBlocks(
        ctx: TransformerContext,
        messages: List<UIMessage>,
        lastUserIndex: Int,
    ): List<KnowledgeContextBlock> {
        val kbId = ctx.assistant.knowledgeBaseId ?: return emptyList()
        val queryText = messages[lastUserIndex].parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (queryText.isEmpty()) return emptyList()

        val settings = get<SettingsStore>().settingsFlow.value
        val kb = settings.findKnowledgeBase(kbId) ?: return emptyList()
        if (kb.documents.isEmpty()) return emptyList()
        val store = get<KnowledgeStoreFactory>().buildStore(kb, settings) ?: return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value =
                    ctx.context.getString(R.string.chat_status_retrieving_knowledge)
                val results = store.searchInDocuments(
                    request = buildRetrievalRequest(queryText, kb),
                    namespace = kb.id.toString(),
                    allowedDocIds = kb.documents.mapTo(mutableSetOf()) { it.id.toString() },
                )
                if (results.isEmpty()) return@withContext emptyList()

                // Raw joined chunks; the renderer owns the <knowledge_base_context> wrapper. estimate
                // over the RENDERED text so the budget accounts for the wrapper actually injected.
                val joined = results.joinToString("\n\n---\n\n") { it.document.content }
                listOf(ragBlock(joined))
            } catch (e: IOException) {
                // Only transient external-service failures are degraded to best-effort: a network
                // error embedding the query or reaching the provider must not block the chat. Other
                // exceptions (notably IllegalArgumentException from a dimension mismatch between the
                // query vector and stored vectors — i.e. a stale-embedding-model bug) are NOT caught
                // here; they propagate so the real defect surfaces instead of being silently masked.
                Log.w(TAG, "retrieveRagBlocks: knowledge retrieval skipped (network)", e)
                emptyList()
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    private fun readDocumentContent(document: UIMessagePart.Document): DocumentExtractionResult {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return DocumentExtractionResult.ParseFailed("invalid file uri: ${document.fileName}")
        return extractor.extract(DocumentSource(file, document.fileName, document.mime))
    }
}
