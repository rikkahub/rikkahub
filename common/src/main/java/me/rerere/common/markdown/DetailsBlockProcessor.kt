package me.rerere.common.markdown

data class DetailsBlockExtraction(
    val content: String,
    val blocks: Map<String, String>
)

data class ParsedDetailsBlock(
    val summaryRaw: String?,
    val bodyRaw: String,
    val openByDefault: Boolean
)

private const val DETAILS_PLACEHOLDER_PREFIX = "RIKKAHUBDETAILSBLOCK"
private val INLINE_CODE_REGEX = Regex("`[^`\n]*`")
private val ATTRIBUTE_VALUE_REGEX = Regex("\"[^\"]*\"|'[^']*'")
private val OPEN_ATTRIBUTE_REGEX = Regex("""(?:^|[\s<])open(?=[\s=/>])""", RegexOption.IGNORE_CASE)
private val SUMMARY_REGEX = Regex("^\\s*(<summary\\b[^>]*>[\\s\\S]*?</summary>)\\s*", setOf(RegexOption.IGNORE_CASE))
private val SIMPLE_CONTAINER_REGEX = Regex(
    "<(p|div)\\b[^>]*>([\\s\\S]*?)</\\1>",
    setOf(RegexOption.IGNORE_CASE)
)

fun extractDetailsBlocks(content: String): DetailsBlockExtraction {
    val codeRanges = findProtectedRanges(content)
    val blocks = linkedMapOf<String, String>()
    val result = StringBuilder()
    var cursor = 0

    while (true) {
        val openStart = findNextDetailsOpen(content, cursor, codeRanges) ?: break
        val blockEnd = findMatchingDetailsEnd(content, openStart, codeRanges) ?: break
        val placeholder = buildDetailsPlaceholder(blocks.size)
        blocks[placeholder] = content.substring(openStart, blockEnd)

        result.append(content, cursor, openStart)
        result.append('\n')
        result.append(placeholder)
        result.append('\n')
        cursor = blockEnd
    }

    result.append(content, cursor, content.length)
    return DetailsBlockExtraction(
        content = result.toString(),
        blocks = blocks
    )
}

fun parseDetailsBlock(rawBlock: String): ParsedDetailsBlock? {
    val openStart = rawBlock.indexOf("<details", ignoreCase = true)
    if (openStart == -1) {
        return null
    }

    val openTagEnd = findTagEnd(rawBlock, openStart) ?: return null
    val closeStart = rawBlock.lastIndexOf("</details", ignoreCase = true)
    if (closeStart == -1 || closeStart <= openTagEnd) {
        return null
    }

    val openingTag = rawBlock.substring(openStart, openTagEnd + 1)
    val innerRaw = rawBlock.substring(openTagEnd + 1, closeStart)
    val summaryMatch = SUMMARY_REGEX.find(innerRaw)
    val summaryRaw = summaryMatch?.groupValues?.get(1)
    val bodyRaw = if (summaryMatch != null) {
        innerRaw.removeRange(summaryMatch.range)
    } else {
        innerRaw
    }
    val openingTagWithoutAttributeValues = ATTRIBUTE_VALUE_REGEX.replace(openingTag) { "\"\"" }

    return ParsedDetailsBlock(
        summaryRaw = summaryRaw,
        bodyRaw = bodyRaw,
        openByDefault = OPEN_ATTRIBUTE_REGEX.containsMatchIn(openingTagWithoutAttributeValues)
    )
}

fun prepareDetailsBodyForMarkdown(bodyRaw: String): String {
    var result = bodyRaw.trim('\r', '\n')
    var previous: String

    do {
        previous = result
        result = SIMPLE_CONTAINER_REGEX.replace(result) { matchResult ->
            "\n${matchResult.groupValues[2].trim('\r', '\n')}\n"
        }.trim('\r', '\n')
    } while (result != previous)

    return result
}

fun isDetailsPlaceholder(text: String): Boolean {
    return text.trim().startsWith(DETAILS_PLACEHOLDER_PREFIX)
}

private fun buildDetailsPlaceholder(index: Int): String {
    return "$DETAILS_PLACEHOLDER_PREFIX$index"
}

private fun findProtectedRanges(content: String): List<IntRange> {
    val inlineCodeRanges = INLINE_CODE_REGEX.findAll(content).map { it.range }
    val fencedCodeRanges = findFencedCodeBlockRanges(content).asSequence()

    return (inlineCodeRanges + fencedCodeRanges)
        .sortedBy { it.first }
        .fold(mutableListOf()) { ranges, range ->
            val previous = ranges.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                ranges[ranges.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                ranges.add(range)
            }
            ranges
        }
}

private fun findFencedCodeBlockRanges(content: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var cursor = 0
    var activeFence: MarkdownFence? = null
    var fenceStart = -1

    while (cursor < content.length) {
        val lineEnd = content.indexOf('\n', cursor).let { if (it == -1) content.length else it }
        val line = content.substring(cursor, lineEnd).removeSuffix("\r")

        if (activeFence == null) {
            val openingFence = parseFence(line)
            if (openingFence != null) {
                activeFence = openingFence
                fenceStart = cursor
            }
        } else if (isClosingFence(line, activeFence)) {
            val fenceEndExclusive = if (lineEnd < content.length) lineEnd + 1 else lineEnd
            ranges.add(fenceStart until fenceEndExclusive)
            activeFence = null
            fenceStart = -1
        }

        cursor = if (lineEnd < content.length) lineEnd + 1 else content.length
    }

    return ranges
}

private fun parseFence(line: String): MarkdownFence? {
    val contentStart = line.indexOfFirst { !it.isWhitespace() }
    if (contentStart !in 0..3) {
        return null
    }

    val fenceChar = line.getOrNull(contentStart)
    if (fenceChar != '`' && fenceChar != '~') {
        return null
    }

    val fenceLength = countRepeatedChar(line, contentStart, fenceChar)
    if (fenceLength < 3) {
        return null
    }

    return MarkdownFence(
        marker = fenceChar,
        length = fenceLength
    )
}

private fun isClosingFence(line: String, fence: MarkdownFence): Boolean {
    val contentStart = line.indexOfFirst { !it.isWhitespace() }
    if (contentStart !in 0..3 || line.getOrNull(contentStart) != fence.marker) {
        return false
    }

    val fenceLength = countRepeatedChar(line, contentStart, fence.marker)
    if (fenceLength < fence.length) {
        return false
    }

    return line.substring(contentStart + fenceLength).isBlank()
}

private fun countRepeatedChar(text: String, startIndex: Int, char: Char): Int {
    var index = startIndex
    while (index < text.length && text[index] == char) {
        index++
    }
    return index - startIndex
}

private fun findMatchingDetailsEnd(
    content: String,
    openingStart: Int,
    protectedRanges: List<IntRange>
): Int? {
    val openingEnd = findTagEnd(content, openingStart) ?: return null
    var cursor = openingEnd + 1
    var depth = 1

    while (cursor < content.length) {
        val nextTag = findNextDetailsTag(content, cursor, protectedRanges) ?: return null
        cursor = nextTag.endExclusive
        depth += if (nextTag.isOpening) 1 else -1
        if (depth == 0) {
            return nextTag.endExclusive
        }
    }

    return null
}

private fun findNextDetailsOpen(
    content: String,
    startIndex: Int,
    protectedRanges: List<IntRange>
): Int? {
    var index = startIndex

    while (index < content.length) {
        val candidate = content.indexOf("<details", index, ignoreCase = true)
        if (candidate == -1) {
            return null
        }

        if (!candidate.isInside(protectedRanges) && hasTagBoundary(content, candidate + "<details".length)) {
            return candidate
        }

        index = candidate + 1
    }

    return null
}

private fun findNextDetailsTag(
    content: String,
    startIndex: Int,
    protectedRanges: List<IntRange>
): DetailsTagMatch? {
    var searchIndex = startIndex

    while (searchIndex < content.length) {
        val nextOpen = content.indexOf("<details", searchIndex, ignoreCase = true).takeIf { it != -1 }
        val nextClose = content.indexOf("</details", searchIndex, ignoreCase = true).takeIf { it != -1 }
        val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: return null
        val isOpening = nextIndex == nextOpen
        val boundaryIndex = nextIndex + if (isOpening) "<details".length else "</details".length

        if (nextIndex.isInside(protectedRanges) || !hasTagBoundary(content, boundaryIndex)) {
            searchIndex = nextIndex + 1
            continue
        }

        val tagEnd = findTagEnd(content, nextIndex) ?: return null
        return DetailsTagMatch(
            isOpening = isOpening,
            endExclusive = tagEnd + 1
        )
    }

    return null
}

private fun findTagEnd(content: String, tagStart: Int): Int? {
    var index = tagStart
    var quote: Char? = null

    while (index < content.length) {
        val char = content[index]
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '>' -> return index
        }
        index++
    }

    return null
}

private fun hasTagBoundary(content: String, index: Int): Boolean {
    if (index >= content.length) {
        return true
    }

    return when (val char = content[index]) {
        '>', '/', ' ', '\t', '\n', '\r' -> true
        else -> !char.isLetterOrDigit()
    }
}

private fun Int.isInside(ranges: List<IntRange>): Boolean {
    return ranges.any { this in it }
}

private data class DetailsTagMatch(
    val isOpening: Boolean,
    val endExclusive: Int
)

private data class MarkdownFence(
    val marker: Char,
    val length: Int
)
