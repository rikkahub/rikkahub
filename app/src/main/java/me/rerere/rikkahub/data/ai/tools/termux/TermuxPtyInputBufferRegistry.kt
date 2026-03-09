package me.rerere.rikkahub.data.ai.tools.termux

import java.util.concurrent.ConcurrentHashMap

internal object TermuxPtyInputBufferRegistry {
    private const val MAX_BUFFER_CHARS = 4_096
    private val sessionBuffers = ConcurrentHashMap<String, String>()

    fun registerSession(sessionId: String) {
        sessionBuffers.putIfAbsent(sessionId, "")
    }

    fun previewInput(
        sessionId: String,
        chars: String,
    ): String {
        if (chars.isEmpty()) return ""
        return limitBuffer((sessionBuffers[sessionId] ?: "") + chars)
    }

    fun commitInput(
        sessionId: String,
        chars: String,
        keepSession: Boolean,
    ) {
        if (!keepSession) {
            removeSession(sessionId)
            return
        }
        if (chars.isEmpty()) return

        val current = sessionBuffers[sessionId].orEmpty()
        val combined = limitBuffer(current + chars)
        val resetInput = chars.any { it == '\u0003' || it == '\u0004' }
        val nextBuffer = when {
            resetInput -> ""
            else -> tailAfterLastCommandBoundary(combined)
        }
        sessionBuffers[sessionId] = limitBuffer(nextBuffer)
    }

    fun removeSession(sessionId: String) {
        sessionBuffers.remove(sessionId)
    }

    fun clearForTests() {
        sessionBuffers.clear()
    }

    private fun tailAfterLastCommandBoundary(value: String): String {
        val lastBoundaryIndex = findLastCommittedBoundary(value)
        if (lastBoundaryIndex == -1) return value
        return value.substring(lastBoundaryIndex + 1)
    }

    private fun findLastCommittedBoundary(value: String): Int {
        var lastBoundaryIndex = -1
        var currentLineStart = 0
        var inSingleQuotes = false
        var inDoubleQuotes = false
        var inBackticks = false
        var escaped = false

        value.forEachIndexed { index, char ->
            when {
                char == '\r' || char == '\n' -> {
                    val continuesCurrentCommand = escaped ||
                        inSingleQuotes ||
                        inDoubleQuotes ||
                        inBackticks ||
                        lineEndsWithContinuationOperator(
                            value = value,
                            lineStart = currentLineStart,
                            lineEndExclusive = index,
                        )
                    escaped = false
                    if (!continuesCurrentCommand) {
                        lastBoundaryIndex = index
                    }
                    currentLineStart = index + 1
                }
                escaped -> {
                    escaped = false
                }
                char == '\\' && !inSingleQuotes -> {
                    escaped = true
                }
                char == '\'' && !inDoubleQuotes && !inBackticks -> {
                    inSingleQuotes = !inSingleQuotes
                }
                char == '"' && !inSingleQuotes && !inBackticks -> {
                    inDoubleQuotes = !inDoubleQuotes
                }
                char == '`' && !inSingleQuotes && !inDoubleQuotes -> {
                    inBackticks = !inBackticks
                }
            }
        }

        return lastBoundaryIndex
    }

    private fun lineEndsWithContinuationOperator(
        value: String,
        lineStart: Int,
        lineEndExclusive: Int,
    ): Boolean {
        var endIndex = lineEndExclusive - 1
        while (endIndex >= lineStart && (value[endIndex] == ' ' || value[endIndex] == '\t')) {
            endIndex--
        }
        if (endIndex < lineStart) return false

        return when (value[endIndex]) {
            '|' -> true
            '&' -> endIndex > lineStart && value[endIndex - 1] == '&'
            '(',
            '{' -> true
            else -> false
        }
    }

    private fun limitBuffer(value: String): String {
        if (value.length <= MAX_BUFFER_CHARS) return value
        return value.takeLast(MAX_BUFFER_CHARS)
    }
}
