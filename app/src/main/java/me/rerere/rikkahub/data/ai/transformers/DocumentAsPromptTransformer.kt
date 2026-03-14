package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private const val MAX_ZIP_LISTED_ENTRIES = 80
private const val MAX_ZIP_PREVIEW_BYTES = 128 * 1024
private const val MAX_ZIP_ENTRY_PREVIEW_BYTES = 24 * 1024

private val zipPreviewableExtensions = setOf(
    "txt", "md", "markdown", "mdx",
    "json", "jsonl",
    "js", "jsx", "ts", "tsx",
    "html", "htm", "css", "scss", "less",
    "xml", "svg",
    "yml", "yaml", "toml", "ini", "cfg", "conf", "properties",
    "py", "java", "kt", "kts", "gradle",
    "c", "h", "cc", "cpp", "cxx", "hpp",
    "go", "rs", "swift", "php", "rb", "sh", "bash", "zsh",
    "sql", "csv",
)

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val prompt = """
                  ## user sent a file: ${document.fileName}
                  <content>
                  ```
                  $content
                  ```
                  </content>
                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
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

    private fun parseZipAsText(file: File): String {
        return ZipFile(file).use { zipFile ->
            val entries = zipFile.entries().asSequence().toList().sortedBy { it.name }
            if (entries.isEmpty()) {
                return@use "[ARCHIVE] ${file.name} (${formatFileSize(file.length())}) is empty."
            }

            var previewBudget = MAX_ZIP_PREVIEW_BYTES
            var listedEntries = 0
            var previewedEntries = 0
            val previewBlocks = mutableListOf<String>()

            val summary = buildString {
                appendLine("[ARCHIVE] ${file.name}")
                appendLine("Size: ${formatFileSize(file.length())}")
                appendLine("Entries: ${entries.size}")
                appendLine()
                appendLine("File list:")

                entries.forEach { entry ->
                    if (listedEntries >= MAX_ZIP_LISTED_ENTRIES) {
                        return@forEach
                    }
                    listedEntries += 1
                    append("- ")
                    append(entry.name)
                    if (entry.isDirectory) {
                        append(" [dir]")
                    } else {
                        append(" (")
                        append(formatFileSize(entry.size.coerceAtLeast(0)))
                        append(")")
                    }
                    appendLine()

                    if (entry.isDirectory || previewBudget <= 0) {
                        return@forEach
                    }
                    val preview = buildZipEntryPreview(
                        entryName = entry.name,
                        entrySize = entry.size,
                        inputStream = zipFile.getInputStream(entry),
                        remainingBudget = previewBudget,
                    ) ?: return@forEach

                    previewedEntries += 1
                    previewBudget -= preview.bytesUsed
                    previewBlocks += preview.block
                }
            }.trimEnd()

            buildString {
                append(summary)
                if (entries.size > listedEntries) {
                    appendLine()
                    appendLine()
                    appendLine("[NOTE] File list truncated. Showing first $listedEntries entries only.")
                }
                if (previewBlocks.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("Previewed text files:")
                    previewBlocks.forEach { block ->
                        appendLine()
                        append(block)
                    }
                }
                if (previewedEntries == 0) {
                    appendLine()
                    appendLine()
                    appendLine("[NOTE] No text files were previewed from this archive.")
                } else if (previewBudget <= 0) {
                    appendLine()
                    appendLine()
                    appendLine("[NOTE] Preview budget reached. Remaining files are listed without inline contents.")
                }
            }.trimEnd()
        }
    }

    internal fun previewZipArchive(file: File): String = parseZipAsText(file)

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when {
                document.isZipArchive() -> parseZipAsText(file)
                document.mime == "application/pdf" -> parsePdfAsText(file)
                document.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    parseDocxAsText(file)

                document.mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    parsePptxAsText(file)

                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }

    private fun UIMessagePart.Document.isZipArchive(): Boolean {
        return mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            fileName.endsWith(".zip", ignoreCase = true)
    }

    private fun buildZipEntryPreview(
        entryName: String,
        entrySize: Long,
        inputStream: InputStream,
        remainingBudget: Int,
    ): ZipEntryPreview? {
        if (!shouldPreviewZipEntry(entryName)) {
            inputStream.close()
            return null
        }

        val maxBytes = minOf(MAX_ZIP_ENTRY_PREVIEW_BYTES, remainingBudget)
        if (maxBytes <= 0) {
            inputStream.close()
            return null
        }

        val sampled = inputStream.use { stream ->
            stream.readAtMost(maxBytes + 1)
        }
        val truncated = sampled.size > maxBytes || entrySize > maxBytes
        val payload = if (sampled.size > maxBytes) sampled.copyOf(maxBytes) else sampled
        if (!payload.isLikelyText()) {
            return null
        }

        val text = payload.toString(Charsets.UTF_8).trimEnd()
        if (text.isBlank()) {
            return null
        }

        return ZipEntryPreview(
            bytesUsed = payload.size,
            block = buildString {
                append("### ")
                append(entryName)
                appendLine()
                append("```")
                append(zipFileLanguage(entryName))
                appendLine()
                appendLine(text)
                if (truncated) {
                    appendLine("[truncated]")
                }
                append("```")
            }
        )
    }

    private fun shouldPreviewZipEntry(entryName: String): Boolean {
        val extension = entryName.substringAfterLast('.', "").lowercase()
        return extension.isBlank() || extension in zipPreviewableExtensions
    }

    private fun zipFileLanguage(entryName: String): String {
        return when (entryName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "rs" -> "rust"
            "go" -> "go"
            "sh", "bash", "zsh" -> "bash"
            "yml", "yaml" -> "yaml"
            "md", "markdown", "mdx" -> "markdown"
            "html", "htm" -> "html"
            "css", "scss", "less" -> "css"
            "xml", "svg" -> "xml"
            "json", "jsonl" -> "json"
            "sql" -> "sql"
            else -> "text"
        }
    }

    private fun ByteArray.isLikelyText(): Boolean {
        if (isEmpty()) return false
        if (any { it == 0.toByte() }) return false

        val text = toString(Charsets.UTF_8)
        if (text.isBlank()) return false

        val replacementCount = text.count { it == '\uFFFD' }
        if (replacementCount > text.length / 10) return false

        val printableCount = text.count { char ->
            !char.isISOControl() || char == '\n' || char == '\r' || char == '\t'
        }
        return printableCount.toDouble() / text.length >= 0.85
    }

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        var remaining = maxBytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun formatFileSize(size: Long): String {
        val safeSize = size.coerceAtLeast(0)
        return when {
            safeSize < 1024 -> "$safeSize B"
            safeSize < 1024 * 1024 -> String.format("%.1f KB", safeSize / 1024.0)
            safeSize < 1024 * 1024 * 1024 -> String.format("%.1f MB", safeSize / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", safeSize / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private data class ZipEntryPreview(
        val bytesUsed: Int,
        val block: String,
    )
}
