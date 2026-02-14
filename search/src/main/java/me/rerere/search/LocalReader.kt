package me.rerere.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

object LocalReader {
    private val NOISE_TAGS = setOf(
        "script", "style", "noscript", "iframe", "nav", "footer", "header", "aside", "form", "svg"
    )

    private val NOISE_SELECTORS = listOf(
        ".ad", ".ads", ".footer", ".header", ".menu", ".nav", ".sidebar", "#footer", "#header", "#menu", "#nav", "#sidebar"
    )

    /**
     * 从 HTML 中提取正文并转换为 Markdown
     */
    fun extract(html: String): String {
        val doc = Jsoup.parse(html)

        // 1. 降噪：移除明显不是正文的标签
        doc.select(NOISE_TAGS.joinToString(",")).remove()
        NOISE_SELECTORS.forEach { doc.select(it).remove() }

        // 2. 定位正文容器
        val body = doc.body()
        val contentElement = findContentElement(body)

        // 3. 转换为 Markdown
        return toMarkdown(contentElement)
    }

    private fun findContentElement(root: Element): Element {
        // 优先寻找 article 或 main 标签
        root.selectFirst("article")?.let { return it }
        root.selectFirst("main")?.let { return it }

        // 使用简单的文字密度评分算法
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

        // 基础分：段落数量和文字长度
        val pCount = element.select("p").size
        val textLength = text.length.toDouble()

        // 标点符号密度分（通常正文会有较多标点）
        val punctuationCount = text.count { it in "，。！？,.!?" }.toDouble()
        val punctuationScore = punctuationCount * 5.0

        // 链接文字比例惩罚
        val linkTextLength = element.select("a").text().length.toDouble()
        val linkDensity = if (textLength > 0) linkTextLength / textLength else 1.0

        return (textLength + (pCount * 20) + punctuationScore) * (1.0 - linkDensity)
    }

    private fun toMarkdown(element: Element): String {
        val sb = StringBuilder()

        fun traverse(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> {
                    val text = node.text().trim()
                    if (text.isNotEmpty()) {
                        sb.append(text)
                    }
                }
                is Element -> {
                    when (node.tagName()) {
                        "h1" -> sb.append("\n# ")
                        "h2" -> sb.append("\n## ")
                        "h3" -> sb.append("\n### ")
                        "h4" -> sb.append("\n#### ")
                        "h5" -> sb.append("\n##### ")
                        "h6" -> sb.append("\n###### ")
                        "p", "div", "section" -> sb.append("\n\n")
                        "br" -> sb.append("\n")
                        "strong", "b" -> sb.append("**")
                        "em", "i" -> sb.append("*")
                        "li" -> sb.append("\n- ")
                        "a" -> {
                            val href = node.attr("href")
                            sb.append("[")
                            node.childNodes().forEach { traverse(it) }
                            sb.append("]($href)")
                            return // 避免重复遍历子节点
                        }
                        "img" -> {
                            val src = node.attr("src")
                            val alt = node.attr("alt")
                            sb.append("\n![${alt.ifEmpty { "image" }}]($src)\n")
                        }
                    }

                    node.childNodes().forEach { traverse(it) }

                    // 闭合标签处理
                    when (node.tagName()) {
                        "strong", "b" -> sb.append("**")
                        "em", "i" -> sb.append("*")
                    }
                }
            }
        }

        element.childNodes().forEach { traverse(it) }
        return sb.toString().trim()
            .replace(Regex("\n{3,}"), "\n\n") // 压缩多余换行
    }
}
