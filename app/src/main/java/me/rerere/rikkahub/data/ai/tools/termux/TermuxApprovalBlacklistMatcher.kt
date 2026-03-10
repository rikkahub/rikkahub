package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

private const val TERMUX_EXEC_TOOL_NAME = "termux_exec"
private const val TERMUX_PYTHON_TOOL_NAME = "termux_python"
private const val WRITE_STDIN_TOOL_NAME = "write_stdin"

object TermuxApprovalBlacklistMatcher {
    private const val FALLBACK_FORCE_APPROVAL_CANDIDATE = "__fallback_force_approval__"
    private val ruleSeparatorRegex = Regex("[,，\\r\\n]+")
    private val whitespaceRegex = Regex("\\s+")
    private const val commandBoundaryRegexPart = "[\\s;&|()'\"`{}\\[\\],]"

    internal fun shouldForceApproval(
        tool: UIMessagePart.Tool,
        blacklistRules: List<String>,
        previewCursor: TermuxPtyInputBufferRegistry.PreviewCursor? = null,
    ): Boolean {
        if (
            tool.toolName != TERMUX_EXEC_TOOL_NAME &&
            tool.toolName != TERMUX_PYTHON_TOOL_NAME &&
            tool.toolName != WRITE_STDIN_TOOL_NAME
        ) {
            return false
        }

        val commandCandidates = extractCommandCandidates(
            tool = tool,
            previewCursor = previewCursor,
        )
        if (commandCandidates.isEmpty()) return false
        if (commandCandidates.contains(FALLBACK_FORCE_APPROVAL_CANDIDATE)) return true
        if (blacklistRules.isEmpty()) return false

        return blacklistRules.any { rule ->
            commandCandidates.any { command -> matchesRule(command, rule) }
        }
    }

    fun parseBlacklistRules(rawRules: String): List<String> {
        return rawRules.split(ruleSeparatorRegex)
            .map { normalizeWhitespace(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    internal fun advanceApprovalPreview(
        tool: UIMessagePart.Tool,
        previewCursor: TermuxPtyInputBufferRegistry.PreviewCursor,
    ) {
        if (tool.toolName != WRITE_STDIN_TOOL_NAME) return
        val input = extractWriteStdinInput(tool) ?: return
        TermuxPtyInputBufferRegistry.commitPreview(
            sessionId = input.sessionId,
            chars = input.chars,
            cursor = previewCursor,
            keepSession = true,
        )
    }

    private fun extractCommandCandidates(
        tool: UIMessagePart.Tool,
        previewCursor: TermuxPtyInputBufferRegistry.PreviewCursor?,
    ): List<String> {
        return when (tool.toolName) {
            TERMUX_EXEC_TOOL_NAME -> extractTermuxExecCandidates(tool)
            TERMUX_PYTHON_TOOL_NAME -> extractTermuxPythonCandidates(tool)
            WRITE_STDIN_TOOL_NAME -> extractWriteStdinCandidates(tool, previewCursor)
            else -> emptyList()
        }
    }

    private fun extractTermuxExecCandidates(tool: UIMessagePart.Tool): List<String> {
        val params = tool.inputAsJson().jsonObject
        val command = params["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return buildMatchCandidates(command)
    }

    private fun extractTermuxPythonCandidates(tool: UIMessagePart.Tool): List<String> {
        val params = tool.inputAsJson().jsonObject
        val code = params["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return buildMatchCandidates(code)
    }

    private fun extractWriteStdinCandidates(
        tool: UIMessagePart.Tool,
        previewCursor: TermuxPtyInputBufferRegistry.PreviewCursor?,
    ): List<String> {
        val input = extractWriteStdinInput(tool) ?: return emptyList()
        val preview = TermuxPtyInputBufferRegistry.previewInputState(
            sessionId = input.sessionId,
            chars = input.chars,
            cursor = previewCursor,
        )
        if (preview.requiresFallbackApproval) {
            return listOf(FALLBACK_FORCE_APPROVAL_CANDIDATE)
        }
        if (preview.text.isBlank()) return emptyList()
        return buildMatchCandidates(preview.text)
    }

    private fun extractWriteStdinInput(tool: UIMessagePart.Tool): WriteStdinInput? {
        val params = tool.inputAsJson().jsonObject
        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        return WriteStdinInput(
            sessionId = sessionId,
            chars = params["chars"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun buildMatchCandidates(rawCommand: String): List<String> {
        val normalized = normalizeWhitespace(rawCommand)
        if (normalized.isBlank()) return emptyList()

        val candidates = linkedSetOf(normalized)
        val firstToken = normalized.substringBefore(" ")
        if (firstToken.contains("/")) {
            val executableName = firstToken.substringAfterLast("/")
            if (executableName.isNotBlank()) {
                val remainder = normalized.removePrefix(firstToken).trimStart()
                val simplified = if (remainder.isBlank()) {
                    executableName
                } else {
                    "$executableName $remainder"
                }
                candidates += simplified
            }
        }
        return candidates.toList()
    }

    private fun matchesRule(command: String, rule: String): Boolean {
        val normalizedRule = normalizeWhitespace(rule)
        if (normalizedRule.isBlank()) return false
        val pattern = Regex(
            "(^|$commandBoundaryRegexPart)${Regex.escape(normalizedRule)}($|$commandBoundaryRegexPart)"
        )
        return pattern.containsMatchIn(command)
    }

    private fun normalizeWhitespace(value: String): String {
        return value.trim().replace(whitespaceRegex, " ")
    }

    private data class WriteStdinInput(
        val sessionId: String,
        val chars: String,
    )
}
