package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

object EpubParser {
    /**
     * Typed extraction: a missing/unreadable OPF or a thrown exception becomes
     * [DocumentExtractionResult.ParseFailed]; a valid book with no readable text becomes
     * [DocumentExtractionResult.Empty] (genuinely empty, not a failure). RAG ingestion uses this so
     * error text is never embedded.
     */
    fun parseTyped(file: File): DocumentExtractionResult {
        return try {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip)
                    ?: return DocumentExtractionResult.ParseFailed("Unable to find OPF file in EPUB")
                val opfDir = opfPath.substringBeforeLast('/', "")

                val opfEntry = zip.getEntry(opfPath)
                    ?: return DocumentExtractionResult.ParseFailed("Unable to read OPF file in EPUB")
                val (manifest, spine) = zip.getInputStream(opfEntry).use { parseOpf(it) }

                val result = StringBuilder()
                for (itemId in spine) {
                    val item = manifest[itemId] ?: continue
                    if (!item.mediaType.contains("html")) continue

                    val itemPath = if (opfDir.isEmpty()) item.href else "$opfDir/${item.href}"
                    val entry = zip.getEntry(itemPath) ?: continue
                    val content = zip.getInputStream(entry).use { parseXhtml(it) }
                    if (content.isNotBlank()) {
                        result.append(content)
                        result.append("\n\n")
                    }
                }

                val text = result.toString().trim()
                if (text.isEmpty()) DocumentExtractionResult.Empty
                else DocumentExtractionResult.Success(text)
            }
        } catch (e: Exception) {
            DocumentExtractionResult.ParseFailed(e.message ?: "Error parsing EPUB file")
        }
    }

    fun parse(file: File): String = when (val result = parseTyped(file)) {
        is DocumentExtractionResult.Success -> result.text
        DocumentExtractionResult.Empty -> "No readable content found in EPUB file"
        is DocumentExtractionResult.ParseFailed -> "Error parsing EPUB file: ${result.reason}"
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        return zip.getInputStream(containerEntry).use { stream ->
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return@use parser.getAttributeValue(null, "full-path")
                }
                parser.next()
            }
            null
        }
    }

    private fun parseOpf(inputStream: InputStream): Pair<Map<String, ManifestItem>, List<String>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id.isNotEmpty()) {
                            manifest[id] = ManifestItem(id, href, mediaType)
                        }
                    }

                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref") ?: ""
                        if (idref.isNotEmpty()) {
                            spine.add(idref)
                        }
                    }
                }
            }
            parser.next()
        }

        return manifest to spine
    }

    // A malformed XHTML chapter propagates to parseTyped's outer catch so the whole extraction
    // becomes ParseFailed; swallowing here (returning "" or partial text) would let RAG embed a
    // corrupt/partial chapter as if it were valid content (issue #83), the same pattern removed
    // from PptxParser.parseSlideXml/parseNotesXml.
    private fun parseXhtml(inputStream: InputStream): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        // Disable DTD processing (XXE / entity-expansion hardening). The requirement is the
        // resulting state, not that the parser accept an explicit toggle: some implementations
        // (e.g. kxml2) keep docdecl off by default and reject setFeature for it. Tolerate that
        // rejection ONLY when the feature is already in the desired (false) state; any other
        // failure to disable it is a real security regression and must propagate.
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        }
        parser.setInput(inputStream, "UTF-8")

        val result = StringBuilder()
        val tagStack = ArrayDeque<String>()
        var inBody = false
        var listCounter = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    tagStack.addLast(tag)

                    when (tag) {
                        "body" -> inBody = true
                        "ol" -> listCounter = 0
                        "li" -> {
                            val parentTag = tagStack.dropLast(1).lastOrNull()
                            if (parentTag == "ol") {
                                listCounter++
                                result.append("$listCounter. ")
                            } else {
                                result.append("- ")
                            }
                        }

                        "br" -> result.append("\n")
                        "img" -> {
                            if (inBody) {
                                val alt = parser.getAttributeValue(null, "alt")
                                if (!alt.isNullOrBlank()) {
                                    result.append("[image: $alt]")
                                }
                            }
                        }

                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            if (inBody) {
                                val level = tag[1].digitToInt()
                                result.append("${"#".repeat(level)} ")
                            }
                        }

                        "strong", "b" -> {
                            if (inBody) result.append("**")
                        }

                        "em", "i" -> {
                            if (inBody) result.append("*")
                        }

                        "hr" -> {
                            if (inBody) result.append("\n---\n")
                        }

                        "blockquote" -> {
                            if (inBody) result.append("> ")
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inBody) {
                        val text = parser.text
                            ?.replace('\n', ' ')
                            ?.replace('\r', ' ')
                            ?.replace("\\s+".toRegex(), " ")
                        if (!text.isNullOrBlank()) {
                            result.append(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tagStack.isNotEmpty()) tagStack.removeLast()

                    when (tag) {
                        "body" -> inBody = false
                        "p", "div" -> {
                            if (inBody) result.append("\n\n")
                        }

                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            if (inBody) result.append("\n\n")
                        }

                        "li" -> {
                            if (inBody) result.append("\n")
                        }

                        "ul", "ol" -> {
                            if (inBody) result.append("\n")
                        }

                        "br" -> {}
                        "strong", "b" -> {
                            if (inBody) result.append("**")
                        }

                        "em", "i" -> {
                            if (inBody) result.append("*")
                        }

                        "blockquote" -> {
                            if (inBody) result.append("\n")
                        }
                    }
                }
            }
            parser.next()
        }

        return result.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
