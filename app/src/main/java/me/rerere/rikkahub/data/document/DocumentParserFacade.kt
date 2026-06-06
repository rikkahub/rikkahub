package me.rerere.rikkahub.data.document

import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.DocumentExtractionResult as ParserResult
import java.io.File

/**
 * Injectable seam over the `:document` module's four binary parsers. Each method returns the
 * parser-level [ParserResult] (Success/Empty/ParseFailed) for a single file; [DocumentTextExtractor]
 * maps that onto the richer app-level [DocumentExtractionResult].
 *
 * The point of the indirection is testability (issue #102): a fake facade lets
 * [DocumentTextExtractor] be unit-tested without real PDF/DOCX/PPTX/EPUB fixtures, while [Default]
 * delegates byte-for-byte to the existing `parseTyped` calls so production behaviour is unchanged.
 */
interface DocumentParserFacade {
    fun parse(type: DocumentType, file: File): ParserResult

    object Default : DocumentParserFacade {
        override fun parse(type: DocumentType, file: File): ParserResult = when (type) {
            DocumentType.Pdf -> PdfParser.parseTyped(file)
            DocumentType.Docx -> DocxParser.parseTyped(file)
            DocumentType.Pptx -> PptxParser.parseTyped(file)
            DocumentType.Epub -> EpubParser.parseTyped(file)
            // PlainText is not a binary parser; the extractor reads it via SafePlainTextReader and
            // never calls the facade for it. Guard so a future caller bug fails loudly, not silently.
            DocumentType.PlainText ->
                error("PlainText is read via SafePlainTextReader, not the parser facade")
        }
    }
}
