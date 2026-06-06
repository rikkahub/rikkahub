package me.rerere.rikkahub.data.document

/**
 * The closed set of document formats the app can extract text from: the four binary parsers in the
 * `:document` module plus a plain-text fallback. This is deliberately a small enum, not a plugin
 * registry — issue #102 explicitly rules out a parser framework for these five fixed formats.
 *
 * [normalizedMime] is the canonical MIME string for the type; [DocumentTypeResolver] maps any
 * incoming MIME/extension onto one of these, so chat attachments and RAG ingestion route identically.
 */
enum class DocumentType(val normalizedMime: String) {
    Pdf("application/pdf"),
    Docx("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    Pptx("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    Epub("application/epub+zip"),
    PlainText("text/plain"),
}
