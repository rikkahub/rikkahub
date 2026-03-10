package me.rerere.rikkahub.data.ai.tools.termux

import java.util.concurrent.ConcurrentHashMap

internal object TermuxPtyInputBufferRegistry {
    private const val MAX_BUFFER_CHARS = 4_096
    private data class SessionBufferState(
        val buffer: String = "",
        val requiresFallbackApproval: Boolean = false,
    )

    data class PreviewState(
        val text: String,
        val requiresFallbackApproval: Boolean,
    )

    private val sessionBuffers = ConcurrentHashMap<String, SessionBufferState>()

    fun registerSession(sessionId: String) {
        sessionBuffers.putIfAbsent(sessionId, SessionBufferState())
    }

    fun previewInput(
        sessionId: String,
        chars: String,
    ): String {
        return previewInputState(sessionId = sessionId, chars = chars).text
    }

    fun previewInputState(
        sessionId: String,
        chars: String,
    ): PreviewState {
        if (chars.isEmpty()) {
            val current = sessionBuffers[sessionId] ?: SessionBufferState()
            return PreviewState(
                text = current.buffer,
                requiresFallbackApproval = current.requiresFallbackApproval,
            )
        }
        val state = applyInput(
            current = sessionBuffers[sessionId] ?: SessionBufferState(),
            chars = chars,
            commitMode = false,
        )
        return PreviewState(
            text = state.buffer,
            requiresFallbackApproval = state.requiresFallbackApproval,
        )
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
        sessionBuffers[sessionId] = applyInput(
            current = sessionBuffers[sessionId] ?: SessionBufferState(),
            chars = chars,
            commitMode = true,
        )
    }

    fun removeSession(sessionId: String) {
        sessionBuffers.remove(sessionId)
    }

    fun clearAllSessions() {
        sessionBuffers.clear()
    }

    fun clearForTests() {
        clearAllSessions()
    }

    private fun applyInput(
        current: SessionBufferState,
        chars: String,
        commitMode: Boolean,
    ): SessionBufferState {
        var buffer = current.buffer
        var requiresFallbackApproval = current.requiresFallbackApproval

        chars.forEach { char ->
            when {
                char == '\u0003' || char == '\u0004' -> {
                    buffer = ""
                    requiresFallbackApproval = false
                }
                char == '\b' || char == '\u007f' -> {
                    buffer = buffer.dropLast(1)
                }
                char == '\u0015' -> {
                    buffer = clearCurrentLine(buffer)
                }
                char == '\u0017' -> {
                    buffer = deleteLastWord(buffer)
                }
                char == '\u001b' || isUnsupportedControlCharacter(char) -> {
                    requiresFallbackApproval = true
                }
                else -> {
                    buffer = limitBuffer(buffer + char)
                    if (commitMode) {
                        val nextBuffer = tailAfterLastCommandBoundary(buffer)
                        if (nextBuffer != buffer) {
                            buffer = limitBuffer(nextBuffer)
                            if (buffer.isEmpty()) {
                                requiresFallbackApproval = false
                            }
                        }
                    }
                }
            }
        }

        return SessionBufferState(
            buffer = limitBuffer(buffer),
            requiresFallbackApproval = requiresFallbackApproval && buffer.isNotEmpty(),
        )
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
            '&' -> endIndex > lineStart && (value[endIndex - 1] == '&' || value[endIndex - 1] == '|')
            '(',
            '{' -> true
            else -> false
        }
    }

    private fun clearCurrentLine(value: String): String {
        val lineStart = value.lastIndexOf('\n').let { index -> if (index == -1) 0 else index + 1 }
        return value.substring(0, lineStart)
    }

    private fun deleteLastWord(value: String): String {
        if (value.isEmpty()) return value
        var index = value.length
        while (index > 0 && value[index - 1].isWhitespace() && value[index - 1] != '\n' && value[index - 1] != '\r') {
            index--
        }
        while (index > 0 && !value[index - 1].isWhitespace()) {
            index--
        }
        return value.substring(0, index)
    }

    private fun isUnsupportedControlCharacter(char: Char): Boolean {
        if (char == '\n' || char == '\r' || char == '\t') return false
        return char.code in 0..31
    }

    private fun limitBuffer(value: String): String {
        if (value.length <= MAX_BUFFER_CHARS) return value
        return value.takeLast(MAX_BUFFER_CHARS)
    }
}
