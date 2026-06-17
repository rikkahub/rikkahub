package me.rerere.rikkahub.data.ai.tools

/**
 * workspace_edit_file 使用的文本替换器, 按 [WorkspaceEditReplacers] 顺序逐级尝试,
 * 前一级找不到任何匹配时才会降级到下一级更宽松的匹配策略。
 */
interface TextReplacer {
    val name: String

    fun findMatches(content: String, oldText: String, newText: String): List<Match>

    data class Match(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
    )
}

data class ReplaceTextResult(
    val updated: String,
    val replacements: Int,
    val occurrences: Int,
    val strategy: String,
)

val WorkspaceEditReplacers: List<TextReplacer> = listOf(
    ExactReplacer,
    LineTrimmedReplacer,
    BlockAnchorReplacer,
)

/**
 * 逐级尝试 [replacers], 使用第一个产生匹配的替换器。
 * 匹配多处且 [replaceAll] 为 false 时抛出 [IllegalArgumentException], 而不是猜测替换哪一处。
 */
fun replaceText(
    content: String,
    oldText: String,
    newText: String,
    replaceAll: Boolean,
    replacers: List<TextReplacer> = WorkspaceEditReplacers,
): ReplaceTextResult {
    require(oldText.isNotEmpty()) { "old_text must not be empty" }
    for (replacer in replacers) {
        val matches = replacer.findMatches(content, oldText, newText)
        if (matches.isEmpty()) continue
        if (!replaceAll) {
            require(matches.size == 1) {
                "old_text matches ${matches.size} locations (strategy: ${replacer.name}); " +
                    "add more surrounding context to make it unique, or set replace_all=true"
            }
        }
        val applied = if (replaceAll) matches.sortedBy { it.start } else listOf(matches.minBy { it.start })
        val builder = StringBuilder(content.length)
        var cursor = 0
        for (match in applied) {
            builder.append(content, cursor, match.start)
            builder.append(match.replacement)
            cursor = match.endExclusive
        }
        builder.append(content, cursor, content.length)
        return ReplaceTextResult(
            updated = builder.toString(),
            replacements = applied.size,
            occurrences = matches.size,
            strategy = replacer.name,
        )
    }
    throw IllegalArgumentException(
        "old_text was not found, even with whitespace-tolerant matching; " +
            "read the file again and copy old_text exactly from its current content"
    )
}

/**
 * 第一级: 精确匹配, 非重叠计数, 与 String.replace 语义一致。
 */
object ExactReplacer : TextReplacer {
    override val name: String = "exact"

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val matches = mutableListOf<TextReplacer.Match>()
        var index = content.indexOf(oldText)
        while (index >= 0) {
            matches += TextReplacer.Match(index, index + oldText.length, newText)
            index = content.indexOf(oldText, index + oldText.length)
        }
        return matches
    }
}

/**
 * 行级窗口匹配的公共骨架: 把 content 与 old_text 拆成行, 滑动比较窗口,
 * 命中后以匹配处首行的真实缩进重排 new_text。
 */
abstract class LineWindowReplacer : TextReplacer {

    protected abstract fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean

    /** old_text 全为空白行时禁用宽松匹配, 避免命中任意空白区域 */
    protected open fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.any { it.isNotEmpty() }

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val rawOldLines = oldText.lines()
        // "foo\n" 语义上是一行, 去掉行尾换行产生的空尾行, new_text 同步处理保持对称
        val dropTrailingEmpty = rawOldLines.size > 1 && rawOldLines.last().isEmpty()
        val oldLines = if (dropTrailingEmpty) rawOldLines.dropLast(1) else rawOldLines
        val oldTrimmed = oldLines.map { it.trim() }
        if (!isApplicable(oldTrimmed)) return emptyList()
        val adjustedNewText = if (dropTrailingEmpty) newText.removeOneTrailingNewline() else newText

        val contentLines = splitLinesWithOffsets(content)
        val matches = mutableListOf<TextReplacer.Match>()
        var index = 0
        while (index + oldLines.size <= contentLines.size) {
            val window = contentLines.subList(index, index + oldLines.size)
            if (windowMatches(window.map { it.text.trim() }, oldTrimmed)) {
                val replacement = reindent(
                    text = adjustedNewText,
                    oldIndent = indentOf(oldLines.first()),
                    newIndent = indentOf(window.first().text),
                )
                matches += TextReplacer.Match(window.first().start, window.last().endExclusive, replacement)
                index += oldLines.size
            } else {
                index++
            }
        }
        return matches
    }
}

/**
 * 第二级: 逐行 trim 后比较, 容忍缩进/行尾空白/CRLF 差异。
 */
object LineTrimmedReplacer : LineWindowReplacer() {
    override val name: String = "line_trimmed"

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed == oldTrimmed
}

/**
 * 第三级: old_text 至少 3 行时, 仅用首尾行做锚点匹配, 容忍中间行的细微差异。
 */
object BlockAnchorReplacer : LineWindowReplacer() {
    override val name: String = "block_anchor"

    override fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.size >= 3 && oldTrimmed.first().isNotEmpty() && oldTrimmed.last().isNotEmpty()

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed.first() == oldTrimmed.first() && windowTrimmed.last() == oldTrimmed.last()
}

private class LineWithOffset(
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

private fun splitLinesWithOffsets(content: String): List<LineWithOffset> {
    val lines = mutableListOf<LineWithOffset>()
    var start = 0
    for (index in content.indices) {
        if (content[index] == '\n') {
            val end = if (index > start && content[index - 1] == '\r') index - 1 else index
            lines += LineWithOffset(start, end, content.substring(start, end))
            start = index + 1
        }
    }
    lines += LineWithOffset(start, content.length, content.substring(start))
    return lines
}

private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

private fun reindent(text: String, oldIndent: String, newIndent: String): String {
    if (oldIndent == newIndent) return text
    return text.lines().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.startsWith(oldIndent) -> newIndent + line.removePrefix(oldIndent)
            else -> line
        }
    }
}

private fun String.removeOneTrailingNewline(): String = when {
    endsWith("\r\n") -> dropLast(2)
    endsWith("\n") -> dropLast(1)
    else -> this
}
