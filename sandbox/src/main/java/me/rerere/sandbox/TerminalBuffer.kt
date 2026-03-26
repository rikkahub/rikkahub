package me.rerere.sandbox

/**
 * A simple terminal output buffer that handles control characters:
 * - `\r`: carriage return (move cursor to beginning of current line)
 * - `\n`: newline
 * - `\b`: backspace
 * - `\t`: tab (align to 8-column boundary)
 * - ANSI escape sequences: stripped
 */
class TerminalBuffer(private val maxChars: Int = 200_000) {
    private val lines = mutableListOf(StringBuilder())
    private var cursorCol = 0

    fun append(raw: String) {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\u001B' -> {
                    i = skipAnsi(raw, i)
                    continue
                }

                c == '\r' -> {
                    cursorCol = 0
                }

                c == '\n' -> {
                    lines.add(StringBuilder())
                    cursorCol = 0
                }

                c == '\b' -> {
                    if (cursorCol > 0) cursorCol--
                }

                c == '\t' -> {
                    val spaces = 8 - (cursorCol % 8)
                    val line = lines.last()
                    repeat(spaces) {
                        writeChar(line, ' ')
                    }
                }

                c >= ' ' -> {
                    writeChar(lines.last(), c)
                }
                // Ignore other control characters
            }
            i++
        }
        trimIfNeeded()
    }

    fun getText(): String = lines.joinToString("\n") { it.toString() }

    fun clear() {
        lines.clear()
        lines.add(StringBuilder())
        cursorCol = 0
    }

    private fun writeChar(line: StringBuilder, c: Char) {
        if (cursorCol < line.length) {
            line[cursorCol] = c
        } else {
            while (line.length < cursorCol) line.append(' ')
            line.append(c)
        }
        cursorCol++
    }

    private fun skipAnsi(text: String, start: Int): Int {
        if (start + 1 >= text.length) return start + 1
        return when (text[start + 1]) {
            '[' -> {
                // CSI: ESC [ ... final_byte(@-~)
                var j = start + 2
                while (j < text.length && text[j] !in '@'..'~') j++
                if (j < text.length) j + 1 else j
            }

            ']' -> {
                // OSC: ESC ] ... (BEL | ESC \)
                var j = start + 2
                while (j < text.length) {
                    if (text[j] == '\u0007') return j + 1
                    if (text[j] == '\u001B' && j + 1 < text.length && text[j + 1] == '\\') return j + 2
                    j++
                }
                j
            }

            else -> start + 2
        }
    }

    private fun trimIfNeeded() {
        var total = lines.sumOf { it.length + 1 }
        while (total > maxChars && lines.size > 1) {
            total -= lines.removeFirst().length + 1
        }
    }
}
