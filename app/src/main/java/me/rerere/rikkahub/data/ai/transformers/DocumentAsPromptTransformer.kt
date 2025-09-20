package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

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
                                val file = document.url.toUri().toFile()
                                val content = when (document.mime) {
                                    "application/pdf" -> parsePdfAsText(file)
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(
                                        file
                                    )

                                    else -> file.readText()
                                }
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
        val pdfDoc = PdfDocument(PdfReader(file))
        return buildString {
            for (i in 0 until pdfDoc.numberOfPages) {
                append("---")
                append("Page ${i + 1}:\n")
                append(
                    PdfTextExtractor.getTextFromPage(
                        /* page = */ pdfDoc.getPage(i + 1),
                    )
                )
                appendLine()
            }
        }
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }
}

private data class ListInfo(
    val level: Int,
    val isNumbered: Boolean,
    val number: Int
)

private class DocxParser {
    companion object {
        fun parse(file: File): String {
            return try {
                file.inputStream().use { fileInputStream ->
                    ZipInputStream(fileInputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            if (entry.name == "word/document.xml") {
                                return parseDocumentXml(zipStream)
                            }
                            entry = zipStream.nextEntry
                        }
                        "Unable to find document content in DOCX file"
                    }
                }
            } catch (e: Exception) {
                "Error parsing DOCX file: ${e.message}"
            }
        }

        private fun parseDocumentXml(inputStream: InputStream): String {
            return try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val document = builder.parse(inputStream)

                val result = StringBuilder()
                val body = document.getElementsByTagName("w:body").item(0)

                if (body != null) {
                    processNode(body, result, 0)
                }

                result.toString().trim()
            } catch (e: Exception) {
                "Error parsing document XML: ${e.message}"
            }
        }

        private fun processNode(node: Node, result: StringBuilder, listLevel: Int) {
            when (node.nodeName) {
                "w:p" -> {
                    val paragraphText = extractParagraphText(node)
                    if (paragraphText.isNotBlank()) {
                        // Check if it's a list item
                        val listInfo = getListInfo(node)
                        if (listInfo != null) {
                            val indent = "  ".repeat(listInfo.level)
                            val marker = if (listInfo.isNumbered) "${listInfo.number}. " else "- "
                            result.append("$indent$marker$paragraphText\n")
                        } else {
                            // Check for heading style
                            val headingLevel = getHeadingLevel(node)
                            if (headingLevel > 0) {
                                val headingPrefix = "#".repeat(headingLevel)
                                result.append("$headingPrefix $paragraphText\n\n")
                            } else {
                                result.append("$paragraphText\n\n")
                            }
                        }
                    }
                }

                "w:tbl" -> {
                    processTable(node, result)
                    result.append("\n")
                }

                else -> {
                    // Process child nodes
                    val children = node.childNodes
                    for (i in 0 until children.length) {
                        processNode(children.item(i), result, listLevel)
                    }
                }
            }
        }

        private fun extractParagraphText(paragraphNode: Node): String {
            val result = StringBuilder()
            val children = paragraphNode.childNodes

            for (i in 0 until children.length) {
                when (children.item(i).nodeName) {
                    "w:r" -> {
                        val runText = extractRunText(children.item(i))
                        result.append(runText)
                    }
                }
            }

            return result.toString().trim()
        }

        private fun extractRunText(runNode: Node): String {
            val result = StringBuilder()
            val children = runNode.childNodes
            var isBold = false
            var isItalic = false

            // Check formatting
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeName == "w:rPr") {
                    isBold = hasChild(child, "w:b")
                    isItalic = hasChild(child, "w:i")
                }
            }

            // Extract text
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeName == "w:t") {
                    var text = child.textContent ?: ""

                    // Apply markdown formatting
                    if (isBold && isItalic) {
                        text = "***$text***"
                    } else if (isBold) {
                        text = "**$text**"
                    } else if (isItalic) {
                        text = "*$text*"
                    }

                    result.append(text)
                }
            }

            return result.toString()
        }

        private fun processTable(tableNode: Node, result: StringBuilder) {
            val rows = mutableListOf<List<String>>()
            val children = tableNode.childNodes

            // Extract table rows
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeName == "w:tr") {
                    val cells = mutableListOf<String>()
                    val rowChildren = child.childNodes

                    for (j in 0 until rowChildren.length) {
                        val rowChild = rowChildren.item(j)
                        if (rowChild.nodeName == "w:tc") {
                            val cellText = extractCellText(rowChild)
                            cells.add(cellText)
                        }
                    }

                    if (cells.isNotEmpty()) {
                        rows.add(cells)
                    }
                }
            }

            // Convert to markdown table
            if (rows.isNotEmpty()) {
                val maxCols = rows.maxOfOrNull { it.size } ?: 0

                // Add table rows
                for ((index, row) in rows.withIndex()) {
                    result.append("| ")
                    for (colIndex in 0 until maxCols) {
                        val cellContent = if (colIndex < row.size) row[colIndex] else ""
                        result.append("$cellContent | ")
                    }
                    result.append("\n")

                    // Add separator after first row (header)
                    if (index == 0) {
                        result.append("| ")
                        repeat(maxCols) {
                            result.append("--- | ")
                        }
                        result.append("\n")
                    }
                }
            }
        }

        private fun extractCellText(cellNode: Node): String {
            val result = StringBuilder()
            val children = cellNode.childNodes

            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeName == "w:p") {
                    val paragraphText = extractParagraphText(child)
                    if (paragraphText.isNotBlank()) {
                        if (result.isNotEmpty()) {
                            result.append(" ")
                        }
                        result.append(paragraphText)
                    }
                }
            }

            return result.toString().trim()
        }

        private fun getListInfo(paragraphNode: Node): ListInfo? {
            // 简化的列表检测逻辑
            val children = paragraphNode.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeName == "w:pPr") {
                    val numPr = findChild(child, "w:numPr")
                    if (numPr != null) {
                        val ilvl =
                            findChild(numPr, "w:ilvl")?.attributes?.getNamedItem("w:val")?.nodeValue?.toIntOrNull() ?: 0
                        val numId = findChild(numPr, "w:numId")?.attributes?.getNamedItem("w:val")?.nodeValue
                        return ListInfo(level = ilvl, isNumbered = numId != null, number = 1) // 简化处理
                    }
                }
            }
            return null
        }

        private fun getHeadingLevel(paragraphNode: Node): Int {
            val children = paragraphNode.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeName == "w:pPr") {
                    val pStyle = findChild(child, "w:pStyle")
                    val styleVal = pStyle?.attributes?.getNamedItem("w:val")?.nodeValue
                    if (styleVal?.startsWith("Heading") == true || styleVal?.startsWith("heading") == true) {
                        return styleVal.last().digitToIntOrNull() ?: 1
                    }
                }
            }
            return 0
        }

        private fun hasChild(node: Node, childName: String): Boolean {
            val children = node.childNodes
            for (i in 0 until children.length) {
                if (children.item(i).nodeName == childName) {
                    return true
                }
            }
            return false
        }

        private fun findChild(node: Node, childName: String): Node? {
            val children = node.childNodes
            for (i in 0 until children.length) {
                if (children.item(i).nodeName == childName) {
                    return children.item(i)
                }
            }
            return null
        }
    }
}
