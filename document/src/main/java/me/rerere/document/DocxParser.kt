package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
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
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            var inBody = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "body" -> inBody = true
                            "p" -> if (inBody) processParagraph(parser, result)
                            "tbl" -> if (inBody) processTable(parser, result)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "body") inBody = false
                    }
                }
                parser.next()
            }

            result.toString().trim()
        } catch (e: Exception) {
            "Error parsing document XML: ${e.message}"
        }
    }

    private fun processParagraph(parser: XmlPullParser, result: StringBuilder) {
        val paragraphStartDepth = parser.depth
        val paragraphContent = StringBuilder()
        var listInfo: ListInfo? = null
        var headingLevel = 0

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "r" -> extractRunText(parser, paragraphContent)
                        "pPr" -> {
                            listInfo = extractListInfo(parser)
                            headingLevel = extractHeadingLevel(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        val paragraphText = paragraphContent.toString().trim()
        if (paragraphText.isNotBlank()) {
            when {
                listInfo != null -> {
                    val indent = "  ".repeat(listInfo.level)
                    val marker = if (listInfo.isNumbered) "${listInfo.number}. " else "- "
                    result.append("$indent$marker$paragraphText\n")
                }
                headingLevel > 0 -> {
                    val headingPrefix = "#".repeat(headingLevel)
                    result.append("$headingPrefix $paragraphText\n\n")
                }
                else -> {
                    result.append("$paragraphText\n\n")
                }
            }
        }
    }

    private fun extractRunText(parser: XmlPullParser, result: StringBuilder) {
        val runStartDepth = parser.depth
        var isBold = false
        var isItalic = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "rPr" -> {
                            val formatting = extractFormatting(parser)
                            isBold = formatting.first
                            isItalic = formatting.second
                        }
                        "t" -> {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                var text = parser.text ?: ""

                                // Apply markdown formatting
                                text = when {
                                    isBold && isItalic -> "***$text***"
                                    isBold -> "**$text**"
                                    isItalic -> "*$text*"
                                    else -> text
                                }

                                result.append(text)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "r" && parser.depth == runStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun extractFormatting(parser: XmlPullParser): Pair<Boolean, Boolean> {
        val rPrStartDepth = parser.depth
        var isBold = false
        var isItalic = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "b" -> isBold = true
                        "i" -> isItalic = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "rPr" && parser.depth == rPrStartDepth) {
                        break
                    }
                }
            }
        }

        return Pair(isBold, isItalic)
    }

    private fun processTable(parser: XmlPullParser, result: StringBuilder) {
        val tableStartDepth = parser.depth
        val rows = mutableListOf<List<String>>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tr") {
                        val cells = extractTableRow(parser)
                        if (cells.isNotEmpty()) {
                            rows.add(cells)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "tbl" && parser.depth == tableStartDepth) {
                        break
                    }
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
        result.append("\n")
    }

    private fun extractTableRow(parser: XmlPullParser): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        val cellText = extractCellText(parser)
                        cells.add(cellText)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "tr" && parser.depth == rowStartDepth) {
                        break
                    }
                }
            }
        }

        return cells
    }

    private fun extractCellText(parser: XmlPullParser): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        val paragraphText = extractCellParagraphText(parser)
                        if (paragraphText.isNotBlank()) {
                            if (result.isNotEmpty()) {
                                result.append(" ")
                            }
                            result.append(paragraphText)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "tc" && parser.depth == cellStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun extractCellParagraphText(parser: XmlPullParser): String {
        val paragraphStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "r") {
                        extractRunText(parser, result)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun extractListInfo(parser: XmlPullParser): ListInfo? {
        val pPrStartDepth = parser.depth
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "numPr" -> {
                            // Found numbering properties
                            val numInfo = extractNumberingInfo(parser)
                            level = numInfo.first
                            isNumbered = numInfo.second
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                        break
                    }
                }
            }
        }

        return if (level > 0 || isNumbered) {
            ListInfo(level = level, isNumbered = isNumbered, number = 1) // 简化处理
        } else null
    }

    private fun extractNumberingInfo(parser: XmlPullParser): Pair<Int, Boolean> {
        val numPrStartDepth = parser.depth
        var level = 0
        var hasNumId = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "ilvl" -> {
                            level = parser.getAttributeValue(null, "val")?.toIntOrNull() ?: 0
                        }
                        "numId" -> {
                            hasNumId = parser.getAttributeValue(null, "val") != null
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "numPr" && parser.depth == numPrStartDepth) {
                        break
                    }
                }
            }
        }

        return Pair(level, hasNumId)
    }

    private fun extractHeadingLevel(parser: XmlPullParser): Int {
        val pPrStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "pStyle") {
                        val styleVal = parser.getAttributeValue(null, "val")
                        if (styleVal?.startsWith("Heading") == true || styleVal?.startsWith("heading") == true) {
                            return styleVal.lastOrNull()?.digitToIntOrNull() ?: 1
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                        break
                    }
                }
            }
        }

        return 0
    }
}
