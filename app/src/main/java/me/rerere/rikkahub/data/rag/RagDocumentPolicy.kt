package me.rerere.rikkahub.data.rag

import me.rerere.rikkahub.data.document.DocumentType
import me.rerere.rikkahub.data.document.DocumentTypeResolver

/**
 * Acceptance policy for knowledge-base (RAG) document ingestion.
 *
 * This reuses issue #102's [DocumentTypeResolver] verbatim as the single source of truth for the
 * supported-format set — it deliberately does NOT recreate [DocumentType] or its resolution rules,
 * so chat attachments and RAG ingestion accept exactly the same formats.
 *
 * Two responsibilities only:
 *  - [pickerMimeTypes]: an *advisory* filter for the system document picker. It advertises only MIME
 *    types the resolver actually accepts, so the picker hint and the authoritative validation agree
 *    (a user never sees, then has rejected, a file the pipeline cannot ingest). `application/json`
 *    and `application/xml` are intentionally absent: the reused resolver does not map them (they are
 *    neither a text subtype nor an allowlisted extension), so advertising them would break that
 *    agreement.
 *  - [resolve]: the *authoritative* check. A `null` result means reject the file before any
 *    copy-to-temp or ingest. There is no raw-text fallback here — that invariant lives in #102's
 *    `DocumentTextExtractor` and is unchanged by this policy.
 */
object RagDocumentPolicy {

    /**
     * Advisory MIME filter for the document picker. Restricted to formats [resolve] accepts; the
     * picker filter is a hint, not a guarantee (some providers report `application/octet-stream`),
     * so [resolve] remains the gate.
     */
    val pickerMimeTypes: Array<String> = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/epub+zip",
        "text/*",
    )

    /** Authoritative type check; `null` => unsupported, reject before any copy/ingest. */
    fun resolve(fileName: String, mime: String?): DocumentType? =
        DocumentTypeResolver.resolve(fileName, mime)
}
