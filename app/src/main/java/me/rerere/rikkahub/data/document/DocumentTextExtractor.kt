package me.rerere.rikkahub.data.document

import me.rerere.document.DocumentExtractionResult as ParserResult
import java.io.File

/**
 * The one central document text extractor used by BOTH chat attachments
 * ([me.rerere.rikkahub.data.ai.transformers.KnowledgeContextTransformer]) and RAG ingestion
 * ([me.rerere.rikkahub.data.rag.IngestKnowledgeBaseUseCase]) — issue #102. It owns the MIME/extension
 * routing (via [DocumentTypeResolver]) that the two paths used to duplicate, so they can no longer
 * drift.
 *
 * Invariant: a parse failure / unsupported type / empty document is returned as a typed
 * [DocumentExtractionResult], never as a [DocumentExtractionResult.Success] carrying an error string.
 * That is what guarantees error text is never embedded as model context or chunked into RAG.
 *
 * Collaborators are injected so the extractor is unit-testable without real binary fixtures:
 *  - [resolver] decides the type,
 *  - [parsers] runs the binary parsers (fakeable),
 *  - [reader] reads plain text behind the issue #84 binary-sniff / size guard.
 */
class DocumentTextExtractor(
    private val resolver: DocumentTypeResolver = DocumentTypeResolver,
    private val parsers: DocumentParserFacade = DocumentParserFacade.Default,
    private val reader: SafePlainTextReader = SafePlainTextReader.Default,
) {
    fun resolve(fileName: String, mime: String?): DocumentType? = resolver.resolve(fileName, mime)

    fun extract(source: DocumentSource): DocumentExtractionResult {
        val file = source.file
        if (!file.exists() || !file.isFile) {
            return DocumentExtractionResult.ParseFailed("File not found: ${source.fileName}")
        }
        val type = resolver.resolve(source.fileName, source.mime)
            ?: return DocumentExtractionResult.UnsupportedType

        return when (type) {
            DocumentType.PlainText -> readPlainText(file, type)
            else -> runBinaryParser(file, type)
        }
    }

    private fun runBinaryParser(file: File, type: DocumentType): DocumentExtractionResult =
        when (val parsed = parsers.parse(type, file)) {
            is ParserResult.Success -> DocumentExtractionResult.Success(
                text = parsed.text,
                type = type,
                normalizedMime = type.normalizedMime,
            )

            ParserResult.Empty -> DocumentExtractionResult.Empty
            is ParserResult.ParseFailed -> DocumentExtractionResult.ParseFailed(parsed.reason)
        }

    private fun readPlainText(file: File, type: DocumentType): DocumentExtractionResult {
        // null means the bytes sniffed as binary or exceeded the size cap (issue #84) — not safe to
        // decode as text, so it is an unsupported input rather than a parser failure.
        val text = reader.read(file) ?: return DocumentExtractionResult.UnsupportedType
        return if (text.isBlank()) DocumentExtractionResult.Empty
        else DocumentExtractionResult.Success(text, type, type.normalizedMime)
    }
}
