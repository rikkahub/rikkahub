package me.rerere.rikkahub.data.ai.tools.termux

object TermuxOutputFormatter {
    fun merge(
        stdout: String,
        stderr: String,
        errMsg: String? = null,
    ): String {
        return buildList {
            stdout.trimEnd().takeIf { it.isNotBlank() }?.let(::add)
            stderr.trimEnd().takeIf { it.isNotBlank() }?.let(::add)
            errMsg?.trimEnd()?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(separator = "\n")
    }

    fun statusSummary(result: TermuxResult): String {
        return buildList {
            if (result.timedOut) add("Timed out")
            result.exitCode?.takeIf { it != 0 }?.let { add("Exit code: $it") }
            result.errCode?.takeIf { result.hasInternalError() }?.let { add("Err code: $it") }
            result.errMsg?.trimEnd()?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(separator = "\n")
    }

    fun normalizeTerminalOutput(raw: String): String {
        if (raw.isEmpty()) return raw

        val output = StringBuilder(raw.length)
        val line = StringBuilder()
        var cursor = 0
        var index = 0

        fun ensureCursorWithinLine() {
            while (line.length < cursor) {
                line.append(' ')
            }
        }

        fun writeChar(ch: Char) {
            ensureCursorWithinLine()
            if (cursor < line.length) {
                line.setCharAt(cursor, ch)
            } else {
                line.append(ch)
            }
            cursor += 1
        }

        fun readCsiParameter(params: String, position: Int, defaultValue: Int): Int {
            val cleanParams = params.removePrefix("?")
            val part = cleanParams.split(';').getOrNull(position)?.takeIf { it.isNotEmpty() }
            return part?.toIntOrNull() ?: defaultValue
        }

        while (index < raw.length) {
            when (val ch = raw[index]) {
                '\u001B' -> {
                    if (index + 1 >= raw.length) break
                    when (raw[index + 1]) {
                        '[' -> {
                            var end = index + 2
                            while (end < raw.length && raw[end] !in '@'..'~') {
                                end += 1
                            }
                            if (end >= raw.length) break

                            val params = raw.substring(index + 2, end)
                            when (raw[end]) {
                                'C' -> cursor += readCsiParameter(params, 0, 1).coerceAtLeast(0)
                                'D' -> cursor = (cursor - readCsiParameter(params, 0, 1)).coerceAtLeast(0)
                                'G' -> cursor = (readCsiParameter(params, 0, 1) - 1).coerceAtLeast(0)
                                'H', 'f' -> cursor = (readCsiParameter(params, 1, 1) - 1).coerceAtLeast(0)
                                'K' -> when (readCsiParameter(params, 0, 0)) {
                                    0 -> {
                                        if (cursor < line.length) {
                                            line.delete(cursor, line.length)
                                        }
                                    }

                                    1 -> {
                                        val eraseUntil = (cursor + 1).coerceAtMost(line.length)
                                        for (i in 0 until eraseUntil) {
                                            line.setCharAt(i, ' ')
                                        }
                                    }

                                    2 -> {
                                        line.setLength(0)
                                        cursor = 0
                                    }
                                }

                                'P' -> {
                                    val count = readCsiParameter(params, 0, 1).coerceAtLeast(0)
                                    if (count > 0 && cursor < line.length) {
                                        line.delete(cursor, (cursor + count).coerceAtMost(line.length))
                                    }
                                }

                                '@' -> {
                                    val count = readCsiParameter(params, 0, 1).coerceAtLeast(0)
                                    if (count > 0) {
                                        ensureCursorWithinLine()
                                        line.insert(cursor, " ".repeat(count))
                                    }
                                }

                                else -> Unit
                            }
                            index = end + 1
                        }

                        ']' -> {
                            var end = index + 2
                            while (end < raw.length) {
                                if (raw[end] == '\u0007') {
                                    end += 1
                                    break
                                }
                                if (raw[end] == '\u001B' && end + 1 < raw.length && raw[end + 1] == '\\') {
                                    end += 2
                                    break
                                }
                                end += 1
                            }
                            index = end.coerceAtMost(raw.length)
                        }

                        else -> index += 2
                    }
                }

                '\r' -> {
                    cursor = 0
                    index += 1
                }

                '\n' -> {
                    output.append(line)
                    output.append('\n')
                    line.setLength(0)
                    cursor = 0
                    index += 1
                }

                '\b',
                '\u007F' -> {
                    cursor = (cursor - 1).coerceAtLeast(0)
                    index += 1
                }

                else -> {
                    if (ch >= ' ' || ch == '\t') {
                        writeChar(ch)
                    }
                    index += 1
                }
            }
        }

        if (line.isNotEmpty()) {
            output.append(line)
        }

        return output.toString()
    }
}
