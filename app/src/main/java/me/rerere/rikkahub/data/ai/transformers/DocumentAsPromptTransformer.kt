package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.document.DocumentExtractionResult
import me.rerere.rikkahub.data.document.DocumentPromptRenderer
import me.rerere.rikkahub.data.document.DocumentSource
import me.rerere.rikkahub.data.document.DocumentTextExtractor

object DocumentAsPromptTransformer : InputMessageTransformer {

    // Shared with RAG ingestion (issue #102): one resolver/extractor, so chat and RAG route formats
    // identically and a parse failure can never be embedded into the prompt as document content.
    private val extractor = DocumentTextExtractor()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = buildPartsWithDocumentPrompts(message.parts, ::readDocumentContent)
                )
            }
        }
    }

    /**
     * Prepends a prompt for each Document part, preserving the original attachment order.
     *
     * The document prompts are inserted as a single ordered block at the front of the list. Inserting
     * each prompt individually at index 0 (the previous behaviour) reversed the order: attachments
     * A B C produced prompts C B A in the message sent to the model (issue #20).
     *
     * Extracted as a pure function over a result-producing reader lambda so it is JVM unit testable
     * without Android URI / disk IO. The reader returns a typed [DocumentExtractionResult]; only
     * [DocumentExtractionResult.Success] is rendered as `<content>` — failures become a metadata-only
     * note (issue #102), never error text masquerading as document content.
     */
    fun buildPartsWithDocumentPrompts(
        parts: List<UIMessagePart>,
        readContent: (UIMessagePart.Document) -> DocumentExtractionResult,
    ): List<UIMessagePart> {
        val documents = parts.filterIsInstance<UIMessagePart.Document>()
        if (documents.isEmpty()) return parts
        val prompts = documents.map { document ->
            UIMessagePart.Text(
                DocumentPromptRenderer.render(document.fileName, readContent(document))
            )
        }
        return prompts + parts
    }

    private fun readDocumentContent(document: UIMessagePart.Document): DocumentExtractionResult {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return DocumentExtractionResult.ParseFailed("invalid file uri: ${document.fileName}")
        return extractor.extract(DocumentSource(file, document.fileName, document.mime))
    }
}
