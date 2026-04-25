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
    fun parse(file: File): String {
        return try {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip)
                    ?: return "Unable to find OPF file in EPUB"
                val opfDir = opfPath.substringBeforeLast('/', "")

                val opfEntry = zip.getEntry(opfPath)
                    ?: return "Unable to read OPF file in EPUB"
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

                result.toString().trim().ifEmpty { "No readable content found in EPUB file" }
            }
        } catch (e: Exception) {
            "Error parsing EPUB file: ${e.message}"
        }
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

    private fun parseXhtml(inputStream: InputStream): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
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
                try {
                    parser.next()
                } catch (_: Exception) {
                    break
                }
            }

            result.toString()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        } catch (e: Exception) {
            ""
        }
    }
}
