package me.rerere.document

import me.rerere.document.pdf.PdfFile
import me.rerere.document.pdf.PdfTextExtractor
import java.io.File

object PdfParser {
    fun parserPdf(file: File): String {
        val pdf = PdfFile(file.readBytes())
        val extractor = PdfTextExtractor(pdf)
        val pages = pdf.pages()
        if (pages.isEmpty()) throw IllegalStateException("No pages found in PDF")
        val result = StringBuilder()
        pages.forEachIndexed { i, page ->
            result.append("---")
            result.append("Page ${i + 1}:\n")
            val text = try {
                extractor.extract(page)
            } catch (e: Exception) {
                "[Failed to extract text from this page: ${e.message}]"
            }
            result.append(text)
            result.appendLine()
        }
        return result.toString()
    }
}
