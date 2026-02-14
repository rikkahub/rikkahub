package me.rerere.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

object LocalReader {
    private const val DEFAULT_MAX_INPUT_CHARS = 1_200_000
    private const val DEFAULT_MAX_OUTPUT_CHARS = 120_000

    private val NOISE_TAGS = setOf(
        "script", "style", "noscript", "iframe", "nav", "footer", "header", "aside", "form", "svg"
    )

    private val NOISE_SELECTORS = listOf(
        ".ad", ".ads", ".footer", ".header", ".menu", ".nav", ".sidebar", "#footer", "#header", "#menu", "#nav", "#sidebar"
    )

    fun extract(
        html: String,
        maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
    ): String {
        val safeInputLimit = maxInputChars.coerceAtLeast(16_384)
        val safeOutputLimit = maxOutputChars.coerceAtLeast(4_096)
        val boundedHtml = if (html.length > safeInputLimit) {
            html.substring(0, safeInputLimit)
        } else {
            html
        }

        val doc = Jsoup.parse(boundedHtml)

        doc.select(NOISE_TAGS.joinToString(",")).remove()
        NOISE_SELECTORS.forEach { doc.select(it).remove() }

        val body = doc.body()
        val contentElement = findContentElement(body)

        return toMarkdown(contentElement, safeOutputLimit)
    }

    private fun findContentElement(root: Element): Element {
        root.selectFirst("article")?.let { return it }
        root.selectFirst("main")?.let { return it }

        var bestElement = root
        var maxScore = -1.0

        root.getAllElements().forEach { element ->
            if (element.tagName() in setOf("div", "section", "article")) {
                val score = calculateScore(element)
                if (score > maxScore) {
                    maxScore = score
                    bestElement = element
                }
            }
        }

        return bestElement
    }

    private fun calculateScore(element: Element): Double {
        val text = element.text()
        if (text.isBlank()) return 0.0

        val pCount = element.select("p").size
        val textLength = text.length.toDouble()
        val punctuationCount = text.count { it in "，。！？；：,.!?;" }.toDouble()
        val punctuationScore = punctuationCount * 5.0

        val linkTextLength = element.select("a").text().length.toDouble()
        val linkDensity = if (textLength > 0) linkTextLength / textLength else 1.0

        return (textLength + (pCount * 20) + punctuationScore) * (1.0 - linkDensity)
    }

    private fun toMarkdown(element: Element, maxOutputChars: Int): String {
        val sb = StringBuilder()

        fun appendLimited(text: String) {
            if (text.isEmpty() || sb.length >= maxOutputChars) return
            val remaining = maxOutputChars - sb.length
            if (text.length <= remaining) {
                sb.append(text)
            } else {
                sb.append(text, 0, remaining)
            }
        }

        fun traverse(node: org.jsoup.nodes.Node) {
            if (sb.length >= maxOutputChars) return

            when (node) {
                is TextNode -> {
                    val text = node.text().trim()
                    if (text.isNotEmpty()) {
                        appendLimited(text)
                    }
                }

                is Element -> {
                    when (node.tagName()) {
                        "h1" -> appendLimited("\n# ")
                        "h2" -> appendLimited("\n## ")
                        "h3" -> appendLimited("\n### ")
                        "h4" -> appendLimited("\n#### ")
                        "h5" -> appendLimited("\n##### ")
                        "h6" -> appendLimited("\n###### ")
                        "p", "div", "section" -> appendLimited("\n\n")
                        "br" -> appendLimited("\n")
                        "strong", "b" -> appendLimited("**")
                        "em", "i" -> appendLimited("*")
                        "li" -> appendLimited("\n- ")
                        "a" -> {
                            val href = node.attr("href")
                            appendLimited("[")
                            node.childNodes().forEach {
                                if (sb.length < maxOutputChars) {
                                    traverse(it)
                                }
                            }
                            appendLimited("]($href)")
                            return
                        }

                        "img" -> {
                            val src = node.attr("src")
                            val alt = node.attr("alt")
                            appendLimited("\n![${alt.ifEmpty { "image" }}]($src)\n")
                        }
                    }

                    node.childNodes().forEach {
                        if (sb.length < maxOutputChars) {
                            traverse(it)
                        }
                    }

                    when (node.tagName()) {
                        "strong", "b" -> appendLimited("**")
                        "em", "i" -> appendLimited("*")
                    }
                }
            }
        }

        element.childNodes().forEach {
            if (sb.length < maxOutputChars) {
                traverse(it)
            }
        }

        val markdown = sb.toString().trim().replace(Regex("\n{3,}"), "\n\n")
        return if (markdown.length > maxOutputChars) {
            markdown.substring(0, maxOutputChars)
        } else {
            markdown
        }
    }
}
