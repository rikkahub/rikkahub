package me.rerere.sandbox

/**
 * A simple terminal output buffer that handles:
 * - Control characters: `\r`, `\n`, `\b`, `\t`
 * - CSI sequences: cursor movement (A/B/C/D/G/H/f), erase (J/K), SGR (m) stripped
 * - OSC sequences: stripped
 */
class TerminalBuffer(private val maxChars: Int = 200_000) {
    private val lines = mutableListOf(StringBuilder())
    private var cursorRow = 0
    private var cursorCol = 0

    fun append(raw: String) {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\u001B' -> {
                    i = handleEscape(raw, i)
                    continue
                }

                c == '\r' -> cursorCol = 0
                c == '\n' -> newline()
                c == '\b' -> {
                    if (cursorCol > 0) cursorCol--
                }

                c == '\t' -> {
                    val spaces = 8 - (cursorCol % 8)
                    repeat(spaces) { writeChar(' ') }
                }

                c >= ' ' -> writeChar(c)
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
        cursorRow = 0
        cursorCol = 0
    }

    // -- Character output --

    private fun writeChar(c: Char) {
        ensureRow(cursorRow)
        val line = lines[cursorRow]
        if (cursorCol < line.length) {
            line[cursorCol] = c
        } else {
            while (line.length < cursorCol) line.append(' ')
            line.append(c)
        }
        cursorCol++
    }

    private fun newline() {
        cursorRow++
        cursorCol = 0
        ensureRow(cursorRow)
    }

    private fun ensureRow(row: Int) {
        while (lines.size <= row) lines.add(StringBuilder())
    }

    // -- Escape sequence handling --

    private fun handleEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) return start + 1
        return when (text[start + 1]) {
            '[' -> handleCsi(text, start)
            ']' -> skipOsc(text, start)
            else -> start + 2 // ESC + single char (e.g. ESC c reset)
        }
    }

    /**
     * Parse and handle CSI (Control Sequence Introducer) sequences: ESC [ params final
     */
    private fun handleCsi(text: String, start: Int): Int {
        var j = start + 2
        val params = StringBuilder()
        // Collect parameter bytes (0x30-0x3F) and intermediate bytes (0x20-0x2F)
        while (j < text.length && text[j] !in '@'..'~') {
            params.append(text[j])
            j++
        }
        if (j >= text.length) return j
        val finalByte = text[j]
        val paramStr = params.toString()

        when (finalByte) {
            // -- Cursor movement --
            'A' -> { // CUU – Cursor Up
                val n = paramStr.toIntOrNull() ?: 1
                cursorRow = (cursorRow - n).coerceAtLeast(0)
            }

            'B' -> { // CUD – Cursor Down
                val n = paramStr.toIntOrNull() ?: 1
                cursorRow += n
                ensureRow(cursorRow)
            }

            'C' -> { // CUF – Cursor Forward
                val n = paramStr.toIntOrNull() ?: 1
                cursorCol += n
            }

            'D' -> { // CUB – Cursor Back
                val n = paramStr.toIntOrNull() ?: 1
                cursorCol = (cursorCol - n).coerceAtLeast(0)
            }

            'G' -> { // CHA – Cursor Horizontal Absolute
                val n = paramStr.toIntOrNull() ?: 1
                cursorCol = (n - 1).coerceAtLeast(0)
            }

            'H', 'f' -> { // CUP – Cursor Position
                val parts = paramStr.split(';')
                val row = (parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1
                val col = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
                cursorRow = row.coerceAtLeast(0)
                cursorCol = col.coerceAtLeast(0)
                ensureRow(cursorRow)
            }

            'd' -> { // VPA – Vertical Position Absolute
                val n = paramStr.toIntOrNull() ?: 1
                cursorRow = (n - 1).coerceAtLeast(0)
                ensureRow(cursorRow)
            }

            // -- Erase --
            'J' -> { // ED – Erase in Display
                val n = paramStr.toIntOrNull() ?: 0
                when (n) {
                    0 -> eraseBelow()
                    1 -> eraseAbove()
                    2, 3 -> clear()
                }
            }

            'K' -> { // EL – Erase in Line
                val n = paramStr.toIntOrNull() ?: 0
                ensureRow(cursorRow)
                val line = lines[cursorRow]
                when (n) {
                    0 -> { // Clear from cursor to end of line
                        if (cursorCol < line.length) line.setLength(cursorCol)
                    }

                    1 -> { // Clear from beginning to cursor
                        for (k in 0 until minOf(cursorCol, line.length)) line[k] = ' '
                    }

                    2 -> { // Clear entire line
                        line.setLength(0)
                        cursorCol = 0
                    }
                }
            }

            // -- Everything else (SGR colors, modes, etc.) — ignore --
        }

        return j + 1
    }

    private fun eraseBelow() {
        ensureRow(cursorRow)
        val line = lines[cursorRow]
        if (cursorCol < line.length) line.setLength(cursorCol)
        // Remove lines below
        while (lines.size > cursorRow + 1) lines.removeLast()
    }

    private fun eraseAbove() {
        // Clear lines above cursor
        for (r in 0 until cursorRow) {
            lines[r].setLength(0)
        }
        // Clear current line up to cursor
        ensureRow(cursorRow)
        val line = lines[cursorRow]
        for (k in 0 until minOf(cursorCol, line.length)) line[k] = ' '
    }

    private fun skipOsc(text: String, start: Int): Int {
        var j = start + 2
        while (j < text.length) {
            if (text[j] == '\u0007') return j + 1
            if (text[j] == '\u001B' && j + 1 < text.length && text[j + 1] == '\\') return j + 2
            j++
        }
        return j
    }

    // -- Buffer management --

    private fun trimIfNeeded() {
        var total = lines.sumOf { it.length + 1 }
        while (total > maxChars && lines.size > 1) {
            total -= lines.removeFirst().length + 1
            cursorRow = (cursorRow - 1).coerceAtLeast(0)
        }
    }
}
