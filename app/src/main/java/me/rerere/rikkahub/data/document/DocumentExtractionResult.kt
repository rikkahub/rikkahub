package me.rerere.rikkahub.data.document

/**
 * Why a document was rejected without being treated as a parse failure. Currently the only cause is
 * the issue #84 extracted-text size cap; modelled as a closed type so the renderer/VM can branch on
 * it and new caps can be added without re-stringly-typing the reason.
 */
enum class DocumentRejectionReason {
    TooLarge,
}

/**
 * Typed outcome of [DocumentTextExtractor.extract]. This is the app-level result shared by chat
 * attachments and RAG ingestion (issue #102), richer than the parser-level
 * [me.rerere.document.DocumentExtractionResult] (which only knows Success/Empty/ParseFailed about a
 * single parser): [Success] also carries the resolved [DocumentType] and its [normalizedMime], and
 * [UnsupportedType] / [Rejected] capture outcomes that exist only at this routing layer.
 *
 * The whole point of the type is that a failure reason can never be mistaken for document content:
 * [ParseFailed.reason] and [Rejected.reason] are diagnostics only and must never be embedded into a
 * model prompt or chunked/embedded for RAG.
 */
sealed interface DocumentExtractionResult {
    data class Success(
        val text: String,
        val type: DocumentType,
        val normalizedMime: String,
    ) : DocumentExtractionResult

    /** The file is not one of the supported types (and did not sniff as plain text). */
    data object UnsupportedType : DocumentExtractionResult

    /** The file resolved to a type but yielded no usable text. */
    data object Empty : DocumentExtractionResult

    /** A parser threw, or the file was missing/unreadable. [reason] is diagnostic-only. */
    data class ParseFailed(val reason: String) : DocumentExtractionResult

    /** A resource limit (issue #84) rejected the document before extraction completed. */
    data class Rejected(val reason: DocumentRejectionReason) : DocumentExtractionResult
}
