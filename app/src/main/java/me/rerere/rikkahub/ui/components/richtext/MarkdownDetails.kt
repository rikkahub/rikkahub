package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.jsoup.Jsoup

private val DETAILS_OPEN_TAG_REGEX = Regex("<details(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val DETAILS_CLOSE_TAG_REGEX = Regex("</details>", RegexOption.IGNORE_CASE)
private val DETAILS_TAG_REGEX = Regex(
    "<details(?:\\s[^>]*)?>|</details>",
    RegexOption.IGNORE_CASE,
)
private val DETAILS_SUMMARY_TAG_REGEX = Regex(
    "^\\s*<summary(?:\\s[^>]*)?>([\\s\\S]*?)</summary>\\s*",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private data class MarkdownDetailsMeta(
    val summaryHtml: String?,
    val summaryText: String,
    val bodyMarkdown: String,
    val isOpenByDefault: Boolean,
)

internal data class MarkdownDetailsBlock(
    val summaryHtml: String?,
    val summaryText: String,
    val contentMarkdown: String,
    val trailingMarkdown: String,
    val consumedNodeCount: Int,
    val isOpenByDefault: Boolean,
    val stableKey: String,
)

internal fun collectMarkdownDetailsBlock(
    nodes: List<ASTNode>,
    startIndex: Int,
    content: String,
): MarkdownDetailsBlock? {
    if (startIndex !in nodes.indices) return null

    val startNode = nodes[startIndex]
    if (startNode.type != MarkdownElementTypes.HTML_BLOCK) return null

    val detailsSource = StringBuilder()
    var depth = 0

    for (index in startIndex until nodes.size) {
        val child = nodes[index]
        val childText = child.textIn(content)

        if (child.type == MarkdownElementTypes.HTML_BLOCK) {
            val scan = scanDetailsHtmlBlock(
                html = childText,
                initialDepth = depth,
                requireOpeningTag = index == startIndex,
            ) ?: return null

            val consumedText = if (scan.closeEndIndex != null) {
                childText.substring(0, scan.closeEndIndex)
            } else {
                childText
            }
            detailsSource.append(consumedText)
            depth = scan.depthAfter

            if (scan.closeEndIndex != null) {
                val meta = detailsSource.toString().parseMarkdownDetailsMeta() ?: return null
                return MarkdownDetailsBlock(
                    summaryHtml = meta.summaryHtml,
                    summaryText = meta.summaryText,
                    contentMarkdown = meta.bodyMarkdown,
                    trailingMarkdown = childText.substring(scan.closeEndIndex),
                    consumedNodeCount = index - startIndex + 1,
                    isOpenByDefault = meta.isOpenByDefault,
                    stableKey = "${startNode.startOffset}:${child.startOffset + scan.closeEndIndex}",
                )
            }
        } else {
            detailsSource.append(childText)
        }
    }

    return null
}

private fun String.parseMarkdownDetailsMeta(): MarkdownDetailsMeta? {
    val openingTag = DETAILS_OPEN_TAG_REGEX.find(this) ?: return null
    if (substring(0, openingTag.range.first).isNotBlank()) return null

    val closingTagRange = findMatchingDetailsClosingTagRange(this) ?: return null
    val innerContent = substring(openingTag.range.last + 1, closingTagRange.first)
    val summaryMatch = DETAILS_SUMMARY_TAG_REGEX.find(innerContent)
    val summaryHtml = summaryMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    val summaryText = summaryHtml?.parseSummaryText() ?: "Details"

    return MarkdownDetailsMeta(
        summaryHtml = summaryHtml,
        summaryText = summaryText,
        bodyMarkdown = if (summaryMatch != null) {
            innerContent.substring(summaryMatch.range.last + 1)
        } else {
            innerContent
        },
        isOpenByDefault = runCatching {
            Jsoup.parseBodyFragment(openingTag.value).selectFirst("details")?.hasAttr("open") == true
        }.getOrDefault(false),
    )
}

private fun String.parseSummaryText(): String {
    return runCatching {
        Jsoup.parseBodyFragment("<summary>$this</summary>").selectFirst("summary")?.text()
    }.getOrNull()?.ifBlank { "Details" } ?: "Details"
}

private fun scanDetailsHtmlBlock(
    html: String,
    initialDepth: Int,
    requireOpeningTag: Boolean,
): DetailsHtmlBlockScan? {
    var depth = initialDepth
    var hasOpeningTag = initialDepth > 0

    if (requireOpeningTag) {
        val openingTag = DETAILS_OPEN_TAG_REGEX.find(html) ?: return null
        if (html.substring(0, openingTag.range.first).isNotBlank()) return null
    }

    DETAILS_TAG_REGEX.findAll(html).forEach { match ->
        if (match.value.startsWith("</", ignoreCase = true)) {
            depth--
            if (hasOpeningTag && depth == 0) {
                return DetailsHtmlBlockScan(
                    closeEndIndex = match.range.last + 1,
                    depthAfter = 0,
                )
            }
        } else {
            hasOpeningTag = true
            depth++
        }
    }

    return if (hasOpeningTag) {
        DetailsHtmlBlockScan(
            closeEndIndex = null,
            depthAfter = depth,
        )
    } else {
        null
    }
}

private fun findMatchingDetailsClosingTagRange(content: String): IntRange? {
    var depth = 0

    DETAILS_TAG_REGEX.findAll(content).forEach { match ->
        if (match.value.startsWith("</", ignoreCase = true)) {
            depth--
            if (depth == 0) {
                return match.range
            }
        } else {
            depth++
        }
    }

    return null
}

private data class DetailsHtmlBlockScan(
    val closeEndIndex: Int?,
    val depthAfter: Int,
)

private fun ASTNode.textIn(content: String): String = content.substring(startOffset, endOffset)
