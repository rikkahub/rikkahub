package me.rerere.rikkahub.data.document

/**
 * Single source of truth for mapping an incoming `(fileName, mime)` pair onto a [DocumentType].
 * Before issue #102 this routing was duplicated as a `when (mime)` block in both
 * `DocumentAsPromptTransformer` (chat) and `DocumentTextExtractor` (RAG), which let the two paths
 * drift. Both now call this resolver, so MIME + extension fallback behaviour is consistent by
 * construction.
 *
 * Resolution order:
 *  1. Exact known MIME, then any `text/` subtype MIME -> [DocumentType.PlainText].
 *  2. Otherwise fall back to the file-name extension (a provider may hand us a generic
 *     `application/octet-stream` with a meaningful `.pdf`/`.docx`/... name).
 *  3. No match -> `null`, which callers treat as an unsupported type (RAG rejects it; chat emits a
 *     metadata-only note). A genuinely unknown binary therefore never falls through to a raw read.
 */
object DocumentTypeResolver {

    private val byMime: Map<String, DocumentType> =
        DocumentType.entries.associateBy { it.normalizedMime }

    private val byExtension: Map<String, DocumentType> = mapOf(
        "pdf" to DocumentType.Pdf,
        "docx" to DocumentType.Docx,
        "pptx" to DocumentType.Pptx,
        "epub" to DocumentType.Epub,
        "txt" to DocumentType.PlainText,
        "md" to DocumentType.PlainText,
        "markdown" to DocumentType.PlainText,
        "text" to DocumentType.PlainText,
    )

    fun resolve(fileName: String, mime: String?): DocumentType? {
        val normalizedMime = mime?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedMime != null) {
            byMime[normalizedMime]?.let { return it }
            if (normalizedMime.startsWith("text/")) return DocumentType.PlainText
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return byExtension[ext]
    }
}
