package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.activeStPresetTemplate
import java.time.Duration
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.random.Random

object SillyTavernMacroTransformer : InputMessageTransformer {
    private const val MAX_MACRO_PASSES = 32
    private val macroRegex = Regex("""\{\{([^{}]*)\}\}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val diceRegex = Regex("""^\s*(\d*)d(\d+)([+-]\d+)?\s*$""", setOf(RegexOption.IGNORE_CASE))
    private val legacyTrimRegex = Regex("""(?:\r?\n)*\{\{trim\}\}(?:\r?\n)*""", setOf(RegexOption.IGNORE_CASE))
    private val scopedCommentRegex = Regex("""\{\{//\}\}[\s\S]*?\{\{///\}\}""")
    private val stRuntimeOnlyMacros = setOf(
        "outlet",
        "instructstorystringprefix",
        "instructstorystringsuffix",
        "instructuserprefix",
        "instructinput",
        "instructusersuffix",
        "instructassistantprefix",
        "instructoutput",
        "instructassistantsuffix",
        "instructseparator",
        "instructsystemprefix",
        "instructsystemsuffix",
        "instructfirstassistantprefix",
        "instructfirstoutputprefix",
        "instructlastassistantprefix",
        "instructlastoutputprefix",
        "instructstop",
        "instructuserfiller",
        "instructsysteminstructionprefix",
        "instructfirstuserprefix",
        "instructfirstinput",
        "instructlastuserprefix",
        "instructlastinput",
        "defaultsystemprompt",
        "instructsystem",
        "instructsystemprompt",
        "systemprompt",
        "exampleseparator",
        "chatseparator",
        "chatstart",
    )

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = ctx.settings.activeStPresetTemplate()
            ?.takeIf { ctx.settings.stPresetEnabled }
        val characterData = ctx.assistant.stCharacterData
        val stRuntimeEnabled = template != null || characterData != null

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
            stRuntimeEnabled = stRuntimeEnabled,
        )
    }

    internal fun applySillyTavernMacros(
        messages: List<UIMessage>,
        env: StMacroEnvironment,
        template: SillyTavernPromptTemplate? = null,
        state: StMacroState = StMacroState(),
        stRuntimeEnabled: Boolean = true,
    ): List<UIMessage> {
        val transformed = messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(
                            text = resolveMacros(part.text, env, state, stRuntimeEnabled = stRuntimeEnabled)
                        )
                        is UIMessagePart.Reasoning -> part.copy(
                            reasoning = resolveMacros(part.reasoning, env, state, stRuntimeEnabled = stRuntimeEnabled)
                        )
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
        stRuntimeEnabled: Boolean,
        rootHash: Int = text.hashCode(),
    ): String {
        if (!text.contains("{{")) return text

        var result = text
        repeat(MAX_MACRO_PASSES) {
            var changed = false
            val scopedResolved = resolveScopedMacros(result, env, state, stRuntimeEnabled)
            if (scopedResolved != result) {
                result = scopedResolved
                changed = true
            }
            result = macroRegex.replace(result) { match ->
                val replacement = replaceMacro(
                    raw = match.value,
                    body = match.groupValues[1],
                    env = env,
                    state = state,
                    macroOffset = match.range.first,
                    rootHash = rootHash,
                    stRuntimeEnabled = stRuntimeEnabled,
                )
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
        stRuntimeEnabled: Boolean,
    ): String {
        var result = scopedCommentRegex.replace(text, "")
        while (true) {
            val scopedMacro = findInnermostScopedMacro(result) ?: return result
            val replacement = evaluateScopedMacro(scopedMacro, result, env, state, stRuntimeEnabled)
            result = result.replaceRange(scopedMacro.open.startIndex, scopedMacro.close.endIndex + 1, replacement)
        }
    }

    private fun replaceMacro(
        raw: String,
        body: String,
        env: StMacroEnvironment,
        state: StMacroState,
        macroOffset: Int,
        rootHash: Int,
        stRuntimeEnabled: Boolean,
    ): String {
        val parsed = ParsedMacro.parse(body) ?: return raw
        val name = parsed.name.lowercase()
        if (!stRuntimeEnabled && isStRuntimeOnlyMacro(name)) return raw

        fun arg(index: Int): String = parsed.args.getOrNull(index).orEmpty()
        fun tail(index: Int): String = parsed.args.drop(index).joinToString("::")

        return when (name) {
            "//", "comment", "noop", "else" -> ""
            "if" -> {
                val inlineIf = parseInlineIfArguments(body)
                if (inlineIf == null) {
                    raw
                } else {
                    if (evaluateCondition(inlineIf.condition, env, state, stRuntimeEnabled)) {
                        resolveMacros(inlineIf.content, env, state, stRuntimeEnabled)
                    } else {
                        ""
                    }
                }
            }
            "trim" -> raw
            "space" -> " ".repeat(parsePositiveInt(arg(0)).coerceAtLeast(1))
            "newline" -> "\n".repeat(parsePositiveInt(arg(0)).coerceAtLeast(1))
            "input" -> env.input
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
            "charversion", "version", "char_version" -> env.charVersion
            "model" -> env.modelName
            "original" -> env.original
            "ismobile" -> env.isMobile.toString()
            "lastmessage", "lastchatmessage" -> env.lastChatMessage
            "lastmessageid" -> env.lastMessageId
            "lastusermessage" -> env.lastUserMessage
            "lastcharmessage" -> env.lastAssistantMessage
            "firstincludedmessageid" -> env.firstIncludedMessageId
            "firstdisplayedmessageid" -> env.firstDisplayedMessageId
            "lastswipeid" -> env.lastSwipeId
            "currentswipeid" -> env.currentSwipeId
            "time" -> env.now.formatTime(arg(0))
            "date" -> env.now.formatDate()
            "weekday" -> env.now.formatWeekday()
            "isotime" -> env.now.format(DateTimeFormatter.ofPattern("HH:mm"))
            "isodate" -> env.now.format(DateTimeFormatter.ISO_LOCAL_DATE)
            "datetimeformat" -> env.now.formatMomentStyle(arg(0))
            "idleduration", "idle_duration" -> formatIdleDuration(env)
            "timediff" -> formatTimeDiff(arg(0), arg(1), env)
            "lastgenerationtype" -> env.generationType
            "hasextension" -> env.hasExtension(arg(0)).toString()
            "maxprompt" -> env.maxPrompt
            "reverse" -> arg(0).reversed()
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
            "hasvar", "varexists" -> state.localVariables.containsKey(arg(0)).toString()
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
            "hasglobalvar", "globalvarexists" -> state.globalVariables.containsKey(arg(0)).toString()
            "deleteglobalvar", "flushglobalvar" -> {
                state.globalVariables.remove(arg(0))
                ""
            }
            "random" -> pickRandom(parsed.args)
            "pick" -> pickDeterministic(parsed.args, body, state, macroOffset, rootHash)
            "banned" -> ""
            "outlet" -> env.outlets[arg(0)] ?: ""
            "instructstorystringprefix" -> env.instructStoryStringPrefix
            "instructstorystringsuffix" -> env.instructStoryStringSuffix
            "instructuserprefix", "instructinput" -> env.instructUserPrefix
            "instructusersuffix" -> env.instructUserSuffix
            "instructassistantprefix", "instructoutput" -> env.instructAssistantPrefix
            "instructassistantsuffix", "instructseparator" -> env.instructAssistantSuffix
            "instructsystemprefix" -> env.instructSystemPrefix
            "instructsystemsuffix" -> env.instructSystemSuffix
            "instructfirstassistantprefix", "instructfirstoutputprefix" -> env.instructFirstAssistantPrefix
            "instructlastassistantprefix", "instructlastoutputprefix" -> env.instructLastAssistantPrefix
            "instructstop" -> env.instructStop
            "instructuserfiller" -> env.instructUserFiller
            "instructsysteminstructionprefix" -> env.instructSystemInstructionPrefix
            "instructfirstuserprefix", "instructfirstinput" -> env.instructFirstUserPrefix
            "instructlastuserprefix", "instructlastinput" -> env.instructLastUserPrefix
            "defaultsystemprompt", "instructsystem", "instructsystemprompt" -> env.defaultSystemPrompt
            "systemprompt" -> env.systemPrompt
            "exampleseparator", "chatseparator" -> env.exampleSeparator
            "chatstart" -> env.chatStart
            "roll" -> rollDice(parsed.args)
            else -> raw
        }
    }

    private fun evaluateScopedMacro(
        macro: ScopedMacroMatch,
        text: String,
        env: StMacroEnvironment,
        state: StMacroState,
        stRuntimeEnabled: Boolean,
    ): String {
        val content = text.substring(macro.open.endIndex + 1, macro.close.startIndex)
        return when (macro.open.name) {
            "trim" -> trimScopedContent(resolveMacros(content, env, state, stRuntimeEnabled))
            "//" -> ""
            "if" -> {
                val split = splitTopLevelElse(content)
                val chosenBranch = if (evaluateCondition(macro.open.args.firstOrNull().orEmpty(), env, state, stRuntimeEnabled)) {
                    split.thenBranch
                } else {
                    split.elseBranch
                }
                trimScopedContent(resolveMacros(chosenBranch.orEmpty(), env, state, stRuntimeEnabled))
            }
            else -> macro.open.raw
        }
    }

    private fun evaluateCondition(
        rawCondition: String,
        env: StMacroEnvironment,
        state: StMacroState,
        stRuntimeEnabled: Boolean,
    ): Boolean {
        var condition = rawCondition.trim()
        var inverted = false
        if (condition.startsWith("!")) {
            inverted = true
            condition = condition.removePrefix("!").trimStart()
        }

        condition = resolveMacros(condition, env, state, stRuntimeEnabled).trim()
        condition = when {
            condition.startsWith(".") -> state.localVariables[condition.removePrefix(".").trim()].orEmpty()
            condition.startsWith("$") -> state.globalVariables[condition.removePrefix("$").trim()].orEmpty()
            else -> resolveBareConditionMacro(condition, env, state, stRuntimeEnabled)
        }

        val truthy = condition.isNotEmpty() && !isFalseBoolean(condition)
        return if (inverted) !truthy else truthy
    }

    private fun resolveBareConditionMacro(
        condition: String,
        env: StMacroEnvironment,
        state: StMacroState,
        stRuntimeEnabled: Boolean,
    ): String {
        if (condition.isBlank()) return ""
        macroRegex.matchEntire(condition)?.groupValues?.getOrNull(1)?.let { wrappedBody ->
            val wrappedParsed = ParsedMacro.parse(wrappedBody)
            if (wrappedParsed != null && !stRuntimeEnabled && isStRuntimeOnlyMacro(wrappedParsed.name.lowercase())) {
                return ""
            }
        }
        val parsed = ParsedMacro.parse(condition) ?: return condition
        if (!stRuntimeEnabled && isStRuntimeOnlyMacro(parsed.name.lowercase())) {
            return ""
        }
        val raw = "{{${condition}}}"
        val resolved = replaceMacro(
            raw = raw,
            body = condition,
            env = env,
            state = state,
            macroOffset = 0,
            rootHash = parsed.hashCode(),
            stRuntimeEnabled = stRuntimeEnabled,
        )
        return if (resolved == raw) condition else resolved
    }

    private fun isStRuntimeOnlyMacro(name: String): Boolean {
        return name in stRuntimeOnlyMacros
    }

    private fun pickRandom(args: List<String>): String {
        val options = parseChoiceOptions(args)
        if (options.isEmpty()) return ""
        return options.random(Random.Default)
    }

    private fun pickDeterministic(
        args: List<String>,
        body: String,
        state: StMacroState,
        macroOffset: Int,
        rootHash: Int,
    ): String {
        val options = parseChoiceOptions(args)
        if (options.isEmpty()) return ""

        val cacheKey = "$rootHash::$macroOffset::$body"
        val existingIndex = state.pickCache[cacheKey]
        if (existingIndex != null && existingIndex in options.indices) {
            return options[existingIndex]
        }

        val nextIndex = Random(cacheKey.hashCode().toLong()).nextInt(options.size)
        state.pickCache[cacheKey] = nextIndex
        return options[nextIndex]
    }

    private fun parseChoiceOptions(args: List<String>): List<String> {
        return when {
            args.size > 1 -> args
            args.isEmpty() -> emptyList()
            else -> args.single()
                .replace("\\,", "\u0000")
                .split(',')
                .map { it.replace("\u0000", ",").trim() }
        }.filter { it.isNotEmpty() }
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

    private fun formatIdleDuration(env: StMacroEnvironment): String {
        val lastUserTime = env.lastUserMessageCreatedAt ?: return "just now"
        return Duration.between(lastUserTime.atZone(env.now.zone), env.now).humanize(withSuffix = false)
    }

    private fun formatTimeDiff(
        left: String,
        right: String,
        env: StMacroEnvironment,
    ): String {
        val leftDateTime = parseDateTime(left, env.now.zone) ?: return ""
        val rightDateTime = parseDateTime(right, env.now.zone) ?: return ""
        return Duration.between(rightDateTime, leftDateTime).humanize(withSuffix = true)
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
