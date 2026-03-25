package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.data.model.effectiveUserPersona
import kotlin.math.max
import kotlin.random.Random

object SillyTavernMacroTransformer : InputMessageTransformer {
    private const val MAX_MACRO_PASSES = 32
    private val macroRegex = Regex("""\{\{([^{}]*)\}\}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val diceRegex = Regex("""^\s*(\d*)d(\d+)([+-]\d+)?\s*$""", setOf(RegexOption.IGNORE_CASE))
    private val legacyTrimRegex = Regex("""(?:\r?\n)*\{\{trim\}\}(?:\r?\n)*""", setOf(RegexOption.IGNORE_CASE))

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = ctx.settings.stPresetTemplate
            ?.takeIf { ctx.settings.stPresetEnabled }
        val characterData = ctx.assistant.stCharacterData
        if (template == null && characterData == null) return messages

        val env = StMacroEnvironment.from(
            ctx = ctx,
            messages = messages,
            template = template,
            characterData = characterData,
        )
        return applySillyTavernMacros(
            messages = messages,
            env = env,
            template = template,
            state = ctx.stMacroState ?: StMacroState(),
        )
    }

    internal fun applySillyTavernMacros(
        messages: List<UIMessage>,
        env: StMacroEnvironment,
        template: SillyTavernPromptTemplate? = null,
        state: StMacroState = StMacroState(),
    ): List<UIMessage> {
        val transformed = messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(text = resolveMacros(part.text, env, state))
                        is UIMessagePart.Reasoning -> part.copy(reasoning = resolveMacros(part.reasoning, env, state))
                        else -> part
                    }
                }
            )
        }.filter { it.isValidToUpload() }

        return if (template?.squashSystemMessages == true) {
            squashSystemMessages(transformed, template)
        } else {
            transformed
        }
    }

    private fun resolveMacros(
        text: String,
        env: StMacroEnvironment,
        state: StMacroState,
    ): String {
        if (!text.contains("{{")) return text

        var result = text
        repeat(MAX_MACRO_PASSES) {
            var changed = false
            val scopedResolved = resolveScopedMacros(result, env, state)
            if (scopedResolved != result) {
                result = scopedResolved
                changed = true
            }
            result = macroRegex.replace(result) { match ->
                val replacement = replaceMacro(match.value, match.groupValues[1], env, state)
                if (replacement != match.value) {
                    changed = true
                }
                replacement
            }
            val postProcessed = postProcessMacros(result)
            if (postProcessed != result) {
                result = postProcessed
                changed = true
            }
            if (!changed) {
                return result
            }
        }

        return postProcessMacros(result)
    }

    private fun resolveScopedMacros(
        text: String,
        env: StMacroEnvironment,
        state: StMacroState,
    ): String {
        var result = text
        while (true) {
            val scopedMacro = findInnermostScopedMacro(result) ?: return result
            val replacement = evaluateScopedMacro(scopedMacro, result, env, state)
            result = result.replaceRange(scopedMacro.open.startIndex, scopedMacro.close.endIndex + 1, replacement)
        }
    }

    private fun replaceMacro(
        raw: String,
        body: String,
        env: StMacroEnvironment,
        state: StMacroState,
    ): String {
        val parsed = ParsedMacro.parse(body) ?: return raw
        val name = parsed.name.lowercase()

        fun arg(index: Int): String = parsed.args.getOrNull(index).orEmpty()
        fun tail(index: Int): String = parsed.args.drop(index).joinToString("::")

        return when (name) {
            "//", "comment", "noop", "else" -> ""
            "if" -> {
                val inlineIf = parseInlineIfArguments(body)
                if (inlineIf == null) {
                    raw
                } else {
                    if (evaluateCondition(inlineIf.condition, env, state)) {
                        resolveMacros(inlineIf.content, env, state)
                    } else {
                        ""
                    }
                }
            }
            "trim" -> raw
            "space" -> " ".repeat(parsePositiveInt(arg(0)).coerceAtLeast(1))
            "newline" -> "\n".repeat(parsePositiveInt(arg(0)).coerceAtLeast(1))
            "user" -> env.user
            "char" -> env.char
            "group", "charifnotgroup" -> env.group
            "groupnotmuted" -> env.groupNotMuted
            "notchar" -> env.notChar
            "description", "chardescription" -> env.characterDescription
            "personality", "charpersonality" -> env.characterPersonality
            "scenario", "charscenario" -> env.scenario
            "persona" -> env.persona
            "charprompt" -> env.charPrompt
            "charinstruction", "charjailbreak" -> env.charInstruction
            "chardepthprompt" -> env.charDepthPrompt
            "creatornotes", "charcreatornotes" -> env.creatorNotes
            "mesexamplesraw" -> env.exampleMessagesRaw
            "mesexamples" -> env.exampleMessagesRaw
            "model" -> env.modelName
            "lastmessage", "lastchatmessage" -> env.lastChatMessage
            "lastusermessage" -> env.lastUserMessage
            "lastcharmessage" -> env.lastAssistantMessage
            "setvar" -> {
                state.localVariables[arg(0)] = tail(1)
                ""
            }
            "getvar" -> state.localVariables[arg(0)] ?: ""
            "addvar" -> {
                state.localVariables[arg(0)] = addValue(state.localVariables[arg(0)], tail(1))
                ""
            }
            "incvar" -> {
                val value = incrementValue(state.localVariables[arg(0)], 1)
                state.localVariables[arg(0)] = value
                value
            }
            "decvar" -> {
                val value = incrementValue(state.localVariables[arg(0)], -1)
                state.localVariables[arg(0)] = value
                value
            }
            "hasvar", "varexists" -> (state.localVariables.containsKey(arg(0))).toString()
            "deletevar", "flushvar" -> {
                state.localVariables.remove(arg(0))
                ""
            }
            "setglobalvar" -> {
                state.globalVariables[arg(0)] = tail(1)
                ""
            }
            "getglobalvar" -> state.globalVariables[arg(0)] ?: ""
            "addglobalvar" -> {
                state.globalVariables[arg(0)] = addValue(state.globalVariables[arg(0)], tail(1))
                ""
            }
            "incglobalvar" -> {
                val value = incrementValue(state.globalVariables[arg(0)], 1)
                state.globalVariables[arg(0)] = value
                value
            }
            "decglobalvar" -> {
                val value = incrementValue(state.globalVariables[arg(0)], -1)
                state.globalVariables[arg(0)] = value
                value
            }
            "random" -> pickRandom(parsed.args)
            "roll" -> rollDice(parsed.args)
            else -> raw
        }
    }

    private fun evaluateScopedMacro(
        macro: ScopedMacroMatch,
        text: String,
        env: StMacroEnvironment,
        state: StMacroState,
    ): String {
        val content = text.substring(macro.open.endIndex + 1, macro.close.startIndex)
        return when (macro.open.name) {
            "trim" -> trimScopedContent(resolveMacros(content, env, state))
            "if" -> {
                val split = splitTopLevelElse(content)
                val chosenBranch = if (evaluateCondition(macro.open.args.firstOrNull().orEmpty(), env, state)) {
                    split.thenBranch
                } else {
                    split.elseBranch
                }
                trimScopedContent(resolveMacros(chosenBranch.orEmpty(), env, state))
            }
            else -> macro.open.raw
        }
    }

    private fun evaluateCondition(
        rawCondition: String,
        env: StMacroEnvironment,
        state: StMacroState,
    ): Boolean {
        var condition = rawCondition.trim()
        var inverted = false
        if (condition.startsWith("!")) {
            inverted = true
            condition = condition.removePrefix("!").trimStart()
        }

        condition = resolveMacros(condition, env, state).trim()
        condition = when {
            condition.startsWith(".") -> state.localVariables[condition.removePrefix(".").trim()].orEmpty()
            condition.startsWith("$") -> state.globalVariables[condition.removePrefix("$").trim()].orEmpty()
            else -> resolveBareConditionMacro(condition, env, state)
        }

        val truthy = condition.isNotEmpty() && !isFalseBoolean(condition)
        return if (inverted) !truthy else truthy
    }

    private fun resolveBareConditionMacro(
        condition: String,
        env: StMacroEnvironment,
        state: StMacroState,
    ): String {
        if (condition.isBlank()) return ""
        val parsed = ParsedMacro.parse(condition) ?: return condition
        val raw = "{{${condition}}}"
        val resolved = replaceMacro(raw, condition, env, state)
        return if (resolved == raw) condition else resolved
    }

    private fun pickRandom(args: List<String>): String {
        val options = when {
            args.size > 1 -> args
            args.isEmpty() -> emptyList()
            else -> args.single()
                .replace("\\,", "\u0000")
                .split(',')
                .map { it.replace("\u0000", ",").trim() }
        }.filter { it.isNotEmpty() }
        if (options.isEmpty()) return ""
        return options.random(Random.Default)
    }

    private fun rollDice(args: List<String>): String {
        var formula = args.joinToString(" ").trim()
        if (formula.isBlank()) return ""
        if (formula.all { it.isDigit() }) {
            formula = "1d$formula"
        }
        val match = diceRegex.matchEntire(formula) ?: return ""
        val count = max(match.groupValues[1].ifBlank { "1" }.toIntOrNull() ?: return "", 1)
        val sides = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return ""
        val modifier = match.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: return ""
        val total = (1..count).sumOf { Random.Default.nextInt(1, sides + 1) } + modifier
        return total.toString()
    }

    private fun addValue(current: String?, delta: String): String {
        val lhs = current?.toDoubleOrNull()
        val rhs = delta.toDoubleOrNull()
        return if (lhs != null && rhs != null) {
            normalizeNumber(lhs + rhs)
        } else {
            current.orEmpty() + delta
        }
    }

    private fun incrementValue(current: String?, delta: Int): String {
        val next = (current?.toDoubleOrNull() ?: 0.0) + delta
        return normalizeNumber(next)
    }

    private fun normalizeNumber(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) {
            longValue.toString()
        } else {
            value.toString()
        }
    }

    private fun parsePositiveInt(value: String): Int {
        return value.trim().toIntOrNull() ?: 1
    }

    private fun parseInlineIfArguments(body: String): InlineIfArguments? {
        val remainder = body.trim().removePrefix("if").trimStart()
        val delimiterIndex = remainder.indexOf("::")
        if (delimiterIndex <= 0) return null
        return InlineIfArguments(
            condition = remainder.substring(0, delimiterIndex).trim(),
            content = remainder.substring(delimiterIndex + 2),
        )
    }

    private fun postProcessMacros(text: String): String {
        return legacyTrimRegex.replace(text, "")
    }

    private fun splitTopLevelElse(content: String): IfBranches {
        var depth = 0
        macroRegex.findAll(content).forEach { match ->
            val tag = MacroTag.from(match) ?: return@forEach
            when {
                tag.isScopedOpeningIf() -> depth++
                tag.isClosing && tag.name == "if" -> depth = (depth - 1).coerceAtLeast(0)
                tag.name == "else" && !tag.isClosing && depth == 0 -> {
                    return IfBranches(
                        thenBranch = content.substring(0, match.range.first),
                        elseBranch = content.substring(match.range.last + 1),
                    )
                }
            }
        }
        return IfBranches(thenBranch = content, elseBranch = null)
    }

    private fun findInnermostScopedMacro(text: String): ScopedMacroMatch? {
        val stack = mutableListOf<MacroTag>()
        macroRegex.findAll(text).forEach { match ->
            val tag = MacroTag.from(match) ?: return@forEach
            when {
                tag.isScopedOpening() -> stack += tag
                tag.isClosing -> {
                    val open = stack.lastOrNull { it.name == tag.name } ?: return@forEach
                    stack.remove(open)
                    return ScopedMacroMatch(open = open, close = tag)
                }
            }
        }
        return null
    }

    private fun trimScopedContent(content: String): String {
        if (content.isBlank()) return ""
        val lines = content.split('\n')
        val baseIndent = lines.firstOrNull { it.trim().isNotEmpty() }
            ?.takeWhile { it == ' ' || it == '\t' }
            ?.length ?: 0
        if (baseIndent == 0) return content.trim()

        return lines.joinToString("\n") { line ->
            val lineIndent = line.takeWhile { it == ' ' || it == '\t' }.length
            if (lineIndent >= baseIndent) {
                line.drop(baseIndent)
            } else {
                line.trimStart()
            }
        }.trim()
    }

    private fun isFalseBoolean(value: String): Boolean {
        return value.trim().lowercase() in setOf("off", "false", "0")
    }

    private fun squashSystemMessages(
        messages: List<UIMessage>,
        template: SillyTavernPromptTemplate,
    ): List<UIMessage> {
        val excludeTexts = setOf(
            template.newChatPrompt.trim(),
            template.newExampleChatPrompt.trim(),
            template.groupNudgePrompt.trim(),
        ).filter { it.isNotBlank() }.toSet()

        val result = mutableListOf<UIMessage>()
        messages.forEach { message ->
            val canSquash = message.role == MessageRole.SYSTEM &&
                message.getTools().isEmpty() &&
                message.parts.all { it is UIMessagePart.Text } &&
                message.toText().trim().isNotBlank() &&
                message.toText().trim() !in excludeTexts
            val last = result.lastOrNull()
            val canMergeWithLast = canSquash &&
                last?.role == MessageRole.SYSTEM &&
                last.getTools().isEmpty() &&
                last.parts.all { it is UIMessagePart.Text } &&
                last.toText().trim() !in excludeTexts

            if (canMergeWithLast) {
                val mergedText = last.toText().trimEnd() + "\n" + message.toText().trim()
                result[result.lastIndex] = UIMessage.system(mergedText)
            } else {
                result += message
            }
        }
        return result
    }
}

private data class IfBranches(
    val thenBranch: String,
    val elseBranch: String?,
)

private data class InlineIfArguments(
    val condition: String,
    val content: String,
)

private data class ScopedMacroMatch(
    val open: MacroTag,
    val close: MacroTag,
)

private data class MacroTag(
    val raw: String,
    val name: String,
    val args: List<String>,
    val startIndex: Int,
    val endIndex: Int,
    val isClosing: Boolean,
) {
    fun isScopedOpening(): Boolean {
        return when (name) {
            "if" -> !isClosing && args.size <= 1
            "trim" -> !isClosing
            else -> false
        }
    }

    fun isScopedOpeningIf(): Boolean {
        return name == "if" && !isClosing && args.size <= 1
    }

    companion object {
        fun from(match: MatchResult): MacroTag? {
            val raw = match.value
            val body = match.groupValues[1].trim()
            if (body.isEmpty()) return null

            val isClosing = body.startsWith("/")
            val normalizedBody = if (isClosing) body.removePrefix("/").trim() else body
            val parsed = ParsedMacro.parse(normalizedBody) ?: return null
            return MacroTag(
                raw = raw,
                name = parsed.name.lowercase(),
                args = parsed.args,
                startIndex = match.range.first,
                endIndex = match.range.last,
                isClosing = isClosing,
            )
        }
    }
}

private data class ParsedMacro(
    val name: String,
    val args: List<String>,
) {
    companion object {
        fun parse(body: String): ParsedMacro? {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null

            val delimiterIndex = listOf(
                trimmed.indexOf("::").takeIf { it >= 0 },
                trimmed.indexOfAny(charArrayOf(' ', '\t', '\r', '\n')).takeIf { it >= 0 },
            ).filterNotNull().minOrNull() ?: trimmed.length

            val name = trimmed.substring(0, delimiterIndex)
            val remainder = trimmed.substring(delimiterIndex)
            val args = when {
                remainder.startsWith("::") -> remainder.removePrefix("::").split("::")
                remainder.isNotBlank() -> listOf(remainder.trim())
                else -> emptyList()
            }
            return ParsedMacro(name = name, args = args)
        }
    }
}

data class StMacroState(
    val localVariables: MutableMap<String, String> = linkedMapOf(),
    val globalVariables: MutableMap<String, String> = linkedMapOf(),
)

internal data class StMacroEnvironment(
    val user: String,
    val char: String,
    val group: String,
    val groupNotMuted: String,
    val notChar: String,
    val characterDescription: String,
    val characterPersonality: String,
    val scenario: String,
    val persona: String,
    val charPrompt: String,
    val charInstruction: String,
    val charDepthPrompt: String,
    val creatorNotes: String,
    val exampleMessagesRaw: String,
    val lastChatMessage: String,
    val lastUserMessage: String,
    val lastAssistantMessage: String,
    val modelName: String,
) {
    companion object {
        fun from(
            ctx: TransformerContext,
            messages: List<UIMessage>,
            template: SillyTavernPromptTemplate?,
            characterData: SillyTavernCharacterData?,
        ): StMacroEnvironment {
            val userName = ctx.settings.effectiveUserName().ifBlank { "user" }
            val charName = ctx.assistant.name.ifBlank {
                characterData?.name?.ifBlank { "assistant" } ?: "assistant"
            }
            val lastChatMessage = messages.lastOrNull { it.role != MessageRole.SYSTEM }?.toText().orEmpty()
            val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()
            val lastAssistantMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.toText().orEmpty()
            val groupValue = charName
            val notCharValue = userName

            return StMacroEnvironment(
                user = userName,
                char = charName,
                group = groupValue,
                groupNotMuted = groupValue,
                notChar = notCharValue,
                characterDescription = characterData?.description.orEmpty(),
                characterPersonality = characterData?.personality.orEmpty(),
                scenario = characterData?.scenario.orEmpty(),
                persona = ctx.settings.effectiveUserPersona(ctx.assistant),
                charPrompt = characterData?.systemPromptOverride.orEmpty(),
                charInstruction = characterData?.postHistoryInstructions.orEmpty(),
                charDepthPrompt = characterData?.depthPrompt?.prompt.orEmpty(),
                creatorNotes = characterData?.creatorNotes.orEmpty(),
                exampleMessagesRaw = characterData?.exampleMessagesRaw.orEmpty(),
                lastChatMessage = lastChatMessage.ifBlank { lastAssistantMessage },
                lastUserMessage = lastUserMessage,
                lastAssistantMessage = lastAssistantMessage,
                modelName = ctx.model.displayName.ifBlank { template?.sourceName.orEmpty() },
            )
        }
    }
}
