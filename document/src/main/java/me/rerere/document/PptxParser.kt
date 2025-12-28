package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private data class SlideContent(
    val slideNumber: Int,
    val content: String,
    val notes: String = ""
)

object PptxParser {
    fun parse(file: File): String {
        return try {
            ZipFile(file).use { zipFile ->
                val slides = mutableListOf<SlideContent>()

                // Find all slide XML files and sort them by number
                val slideEntries = zipFile.entries().toList()
                    .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { entry ->
                        entry.name.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0
                    }

                if (slideEntries.isEmpty()) {
                    return "No slides found in PPTX file"
                }

                // Parse each slide
                slideEntries.forEachIndexed { index, entry ->
                    val slideNumber = index + 1
                    val slideContent = zipFile.getInputStream(entry).use { stream ->
                        parseSlideXml(stream)
                    }

                    // Try to get notes for this slide
                    val notesEntry = zipFile.getEntry("ppt/notesSlides/notesSlide${slideNumber}.xml")
                    val notes = if (notesEntry != null) {
                        zipFile.getInputStream(notesEntry).use { stream ->
                            parseNotesXml(stream)
                        }
                    } else ""

                    slides.add(SlideContent(slideNumber, slideContent, notes))
                }

                // Format output
                formatOutput(slides)
            }
        } catch (e: Exception) {
            "Error parsing PPTX file: ${e.message}"
        }
    }

    private fun formatOutput(slides: List<SlideContent>): String {
        val result = StringBuilder()

        slides.forEach { slide ->
            result.append("## Slide ${slide.slideNumber}\n\n")
            result.append(slide.content)

            if (slide.notes.isNotBlank()) {
                result.append("\n### Speaker Notes\n\n")
                result.append(slide.notes)
            }

            result.append("\n")
        }

        return result.toString().trim()
    }

    private fun parseSlideXml(inputStream: InputStream): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sp" -> processShape(parser, result)  // Text box/shape
                            "graphicFrame" -> processGraphicFrame(parser, result)  // Table
                        }
                    }
                }
                parser.next()
            }

            result.toString()
        } catch (e: Exception) {
            "Error parsing slide XML: ${e.message}\n"
        }
    }

    private fun processShape(parser: XmlPullParser, result: StringBuilder) {
        val shapeStartDepth = parser.depth
        val textContent = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> {
                            // Start of paragraph - check for bullet/numbering
                            val paragraphInfo = processParagraph(parser, textContent)
                            hasBullet = paragraphInfo.first
                            bulletLevel = paragraphInfo.second
                            isNumbered = paragraphInfo.third
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                }
            }
        }

        val text = textContent.toString().trim()
        if (text.isNotBlank()) {
            result.append(text)
            result.append("\n\n")
        }
    }

    private fun processParagraph(parser: XmlPullParser, result: StringBuilder): Triple<Boolean, Int, Boolean> {
        val paragraphStartDepth = parser.depth
        val paragraphText = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "pPr" -> {
                            // Paragraph properties - check for bullets
                            val bulletInfo = extractBulletInfo(parser)
                            hasBullet = bulletInfo.first
                            bulletLevel = bulletInfo.second
                            isNumbered = bulletInfo.third
                        }

                        "r" -> {
                            // Text run
                            extractTextRun(parser, paragraphText)
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

        val text = paragraphText.toString().trim()
        if (text.isNotBlank()) {
            if (hasBullet) {
                val indent = "  ".repeat(bulletLevel)
                val marker = if (isNumbered) "1. " else "- "
                result.append("$indent$marker$text\n")
            } else {
                result.append("$text\n")
            }
        }

        return Triple(hasBullet, bulletLevel, isNumbered)
    }

    private fun extractBulletInfo(parser: XmlPullParser): Triple<Boolean, Int, Boolean> {
        val pPrStartDepth = parser.depth
        var hasBullet = false
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "buChar" -> {
                            hasBullet = true
                            isNumbered = false
                        }

                        "buAutoNum" -> {
                            hasBullet = true
                            isNumbered = true
                        }

                        "lvl" -> {
                            parser.getAttributeValue(null, "val")?.let {
                                level = it.toIntOrNull() ?: 0
                            }
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

        return Triple(hasBullet, level, isNumbered)
    }

    private fun extractTextRun(parser: XmlPullParser, result: StringBuilder) {
        val runStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            result.append(parser.text ?: "")
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

    private fun processGraphicFrame(parser: XmlPullParser, result: StringBuilder) {
        val frameStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tbl") {
                        processTable(parser, result)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "graphicFrame" && parser.depth == frameStartDepth) {
                        break
                    }
                }
            }
        }
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
            result.append("\n")
        }
    }

    private fun extractTableRow(parser: XmlPullParser): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        val cellText = extractTableCell(parser)
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

    private fun extractTableCell(parser: XmlPullParser): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            if (result.isNotEmpty()) {
                                result.append(" ")
                            }
                            result.append(parser.text ?: "")
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

    private fun parseNotesXml(inputStream: InputStream): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            var inNotesShape = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sp" -> {
                                // Check if this is a notes text shape (not the slide preview)
                                inNotesShape = isNotesTextShape(parser)
                                if (inNotesShape) {
                                    extractShapeText(parser, result)
                                }
                            }
                        }
                    }
                }
                parser.next()
            }

            result.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun isNotesTextShape(parser: XmlPullParser): Boolean {
        // Notes text typically has ph type="body"
        val currentDepth = parser.depth
        val originalPosition = parser

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "ph") {
                        val type = parser.getAttributeValue(null, "type")
                        return type == "body"
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth <= currentDepth) {
                        return false
                    }
                }
            }
        }
        return false
    }

    private fun extractShapeText(parser: XmlPullParser, result: StringBuilder) {
        val shapeStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            result.append(parser.text ?: "")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                    if (parser.name == "p") {
                        result.append("\n")
                    }
                }
            }
        }
    }
}
