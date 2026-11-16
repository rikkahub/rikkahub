package me.rerere.rikkahub.ui.components.message

private val ListMarkerRegex = Regex("^ {0,3}([-*+]|\\d{1,9}[.)])\\s")
private val FenceRegex = Regex("^ {0,3}(`{3,}|~{3,})")

/**
 * 把 Markdown 按空行拆成段落，用于把一条助手回复拆成多个气泡显示。
 *
 * 代码块、`$$`/`\[` 公式块里的空行不拆；空行后缩进的内容是上一个列表项的延续，并回上一段。
 * 每段内容原样保留。
 */
fun splitMarkdownParagraphs(text: String): List<String> {
    val chunks = mutableListOf<MutableList<String>>()
    var current: MutableList<String>? = null
    // 当前所在代码块的围栏，比如 ``` 或 ~~~~
    var fence: String? = null
    var mathEnd: String? = null

    for (line in text.lines()) {
        val trimmed = line.trim()
        when {
            fence != null -> {
                if (trimmed.startsWith(fence) && trimmed.all { it == fence[0] }) fence = null
            }

            mathEnd != null -> {
                if (trimmed == mathEnd) mathEnd = null
            }

            trimmed.isEmpty() -> {
                current = null
                continue
            }

            else -> {
                FenceRegex.find(line)?.let { fence = it.groupValues[1] }
                when (trimmed) {
                    "$$" -> mathEnd = "$$"
                    "\\[" -> mathEnd = "\\]"
                }
            }
        }

        val chunk = current ?: run {
            val previous = chunks.lastOrNull()
            val continuesList = previous != null &&
                (line.startsWith(" ") || line.startsWith("\t")) &&
                previous.any { ListMarkerRegex.containsMatchIn(it) }
            if (continuesList) {
                previous.add("")
                previous
            } else {
                mutableListOf<String>().also { chunks.add(it) }
            }
        }
        chunk.add(line)
        current = chunk
    }
    return chunks.map { it.joinToString("\n") }
}
