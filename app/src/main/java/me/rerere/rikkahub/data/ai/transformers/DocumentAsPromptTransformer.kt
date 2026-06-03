package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
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
     * A B C produced prompts C B A in the message sent to the model.
     *
     * Extracted as a pure function over a content-reader lambda so it is JVM unit testable without
     * Android URI / disk IO.
     */
    fun buildPartsWithDocumentPrompts(
        parts: List<UIMessagePart>,
        readContent: (UIMessagePart.Document) -> String,
    ): List<UIMessagePart> {
        val documents = parts.filterIsInstance<UIMessagePart.Document>()
        if (documents.isEmpty()) return parts
        val prompts = documents.map { document ->
            val content = readContent(document)
            UIMessagePart.Text(
                """
                ## user sent a file: ${document.fileName}
                <content>
                ```
                $content
                ```
                </content>
                """.trimMargin()
            )
        }
        return prompts + parts
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }
}
