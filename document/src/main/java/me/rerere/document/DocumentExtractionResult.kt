package me.rerere.document

/**
 * Typed outcome of extracting text from a document.
 *
 * Distinguishes real content from parse failure and empty-but-valid documents so callers (notably
 * RAG ingestion) never mistake a human-readable parser error string for document content. The
 * [ParseFailed.reason] is for user-facing diagnostics only — it must never be chunked, embedded, or
 * persisted.
 */
sealed interface DocumentExtractionResult {
    data class Success(val text: String) : DocumentExtractionResult
    data object Empty : DocumentExtractionResult
    data class ParseFailed(val reason: String) : DocumentExtractionResult
}
