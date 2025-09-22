package me.rerere.document

import org.dom4j.Element
import org.dom4j.io.SAXReader
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

private data class ListInfo(
    val level: Int,
    val isNumbered: Boolean,
    val number: Int
)

object DocxParser {
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
            val reader = SAXReader()
            val document = reader.read(inputStream)

            val result = StringBuilder()
            val body = document.rootElement.element("body")

            if (body != null) {
                processElement(body, result, 0)
            }

            result.toString().trim()
        } catch (e: Exception) {
            "Error parsing document XML: ${e.message}"
        }
    }

    private fun processElement(element: Element, result: StringBuilder, listLevel: Int) {
        when (element.name) {
            "p" -> {
                val paragraphText = extractParagraphText(element)
                if (paragraphText.isNotBlank()) {
                    // Check if it's a list item
                    val listInfo = getListInfo(element)
                    if (listInfo != null) {
                        val indent = "  ".repeat(listInfo.level)
                        val marker = if (listInfo.isNumbered) "${listInfo.number}. " else "- "
                        result.append("$indent$marker$paragraphText\n")
                    } else {
                        // Check for heading style
                        val headingLevel = getHeadingLevel(element)
                        if (headingLevel > 0) {
                            val headingPrefix = "#".repeat(headingLevel)
                            result.append("$headingPrefix $paragraphText\n\n")
                        } else {
                            result.append("$paragraphText\n\n")
                        }
                    }
                }
            }

            "tbl" -> {
                processTable(element, result)
                result.append("\n")
            }

            else -> {
                // Process child elements
                element.elements().forEach { child ->
                    processElement(child, result, listLevel)
                }
            }
        }
    }

    private fun extractParagraphText(paragraphElement: Element): String {
        val result = StringBuilder()

        paragraphElement.elements("r").forEach { runElement ->
            val runText = extractRunText(runElement)
            result.append(runText)
        }

        return result.toString().trim()
    }

    private fun extractRunText(runElement: Element): String {
        val result = StringBuilder()
        var isBold = false
        var isItalic = false

        // Check formatting
        val rPr = runElement.element("rPr")
        if (rPr != null) {
            isBold = rPr.element("b") != null
            isItalic = rPr.element("i") != null
        }

        // Extract text
        runElement.elements("t").forEach { textElement ->
            var text = textElement.text ?: ""

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

        return result.toString()
    }

    private fun processTable(tableElement: Element, result: StringBuilder) {
        val rows = mutableListOf<List<String>>()

        // Extract table rows
        tableElement.elements("tr").forEach { rowElement ->
            val cells = mutableListOf<String>()

            rowElement.elements("tc").forEach { cellElement ->
                val cellText = extractCellText(cellElement)
                cells.add(cellText)
            }

            if (cells.isNotEmpty()) {
                rows.add(cells)
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

    private fun extractCellText(cellElement: Element): String {
        val result = StringBuilder()

        cellElement.elements("p").forEach { paragraphElement ->
            val paragraphText = extractParagraphText(paragraphElement)
            if (paragraphText.isNotBlank()) {
                if (result.isNotEmpty()) {
                    result.append(" ")
                }
                result.append(paragraphText)
            }
        }

        return result.toString().trim()
    }

    private fun getListInfo(paragraphElement: Element): ListInfo? {
        // 简化的列表检测逻辑
        val pPr = paragraphElement.element("pPr")
        if (pPr != null) {
            val numPr = pPr.element("numPr")
            if (numPr != null) {
                val ilvl = numPr.element("ilvl")?.attributeValue("val")?.toIntOrNull() ?: 0
                val numId = numPr.element("numId")?.attributeValue("val")
                return ListInfo(level = ilvl, isNumbered = numId != null, number = 1) // 简化处理
            }
        }
        return null
    }

    private fun getHeadingLevel(paragraphElement: Element): Int {
        val pPr = paragraphElement.element("pPr")
        if (pPr != null) {
            val pStyle = pPr.element("pStyle")
            val styleVal = pStyle?.attributeValue("val")
            if (styleVal?.startsWith("Heading") == true || styleVal?.startsWith("heading") == true) {
                return styleVal.lastOrNull()?.digitToIntOrNull() ?: 1
            }
        }
        return 0
    }
}
