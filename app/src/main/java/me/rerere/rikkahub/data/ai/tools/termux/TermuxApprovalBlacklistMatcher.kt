package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

private const val TERMUX_EXEC_TOOL_NAME = "termux_exec"
private const val TERMUX_PYTHON_TOOL_NAME = "termux_python"

object TermuxApprovalBlacklistMatcher {
    private val ruleSeparatorRegex = Regex("[,，\\r\\n]+")
    private val whitespaceRegex = Regex("\\s+")
    private const val commandBoundaryRegexPart = "[\\s;&|()'\"`{}\\[\\],]"

    internal fun shouldForceApproval(
        tool: UIMessagePart.Tool,
        blacklistRules: List<String>,
    ): Boolean {
        if (tool.toolName != TERMUX_EXEC_TOOL_NAME && tool.toolName != TERMUX_PYTHON_TOOL_NAME) {
            return false
        }

        val commandCandidates = extractCommandCandidates(
            tool = tool,
        )
        if (commandCandidates.isEmpty()) return false
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

    private fun extractCommandCandidates(
        tool: UIMessagePart.Tool,
    ): List<String> {
        return when (tool.toolName) {
            TERMUX_EXEC_TOOL_NAME -> extractTermuxExecCandidates(tool)
            TERMUX_PYTHON_TOOL_NAME -> extractTermuxPythonCandidates(tool)
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
}
