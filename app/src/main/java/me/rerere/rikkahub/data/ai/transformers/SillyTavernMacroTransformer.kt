package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.activeStPresetTemplate
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.data.model.effectiveUserPersona
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime as JavaLocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

object SillyTavernMacroTransformer : InputMessageTransformer {
    private const val MAX_MACRO_PASSES = 32
    private val macroRegex = Regex("""\{\{([^{}]*)\}\}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val diceRegex = Regex("""^\s*(\d*)d(\d+)([+-]\d+)?\s*$""", setOf(RegexOption.IGNORE_CASE))
    private val legacyTrimRegex = Regex("""(?:\r?\n)*\{\{trim\}\}(?:\r?\n)*""", setOf(RegexOption.IGNORE_CASE))
    private val scopedCommentRegex = Regex("""\{\{//\}\}[\s\S]*?\{\{///\}\}""")

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = ctx.settings.activeStPresetTemplate()
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
        rootHash: Int = text.hashCode(),
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
                val replacement = replaceMacro(
                    raw = match.value,
                    body = match.groupValues[1],
                    env = env,
                    state = state,
                    macroOffset = match.range.first,
                    rootHash = rootHash,
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
    ): String {
        var result = scopedCommentRegex.replace(text, "")
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
        macroOffset: Int,
        rootHash: Int,
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
    ): String {
        val content = text.substring(macro.open.endIndex + 1, macro.close.startIndex)
        return when (macro.open.name) {
            "trim" -> trimScopedContent(resolveMacros(content, env, state))
            "//" -> ""
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
        val resolved = replaceMacro(
            raw = raw,
            body = condition,
            env = env,
            state = state,
            macroOffset = 0,
            rootHash = parsed.hashCode(),
        )
        return if (resolved == raw) condition else resolved
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

    private fun parseDateTime(
        rawValue: String,
        zoneId: ZoneId,
    ): ZonedDateTime? {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return null

        runCatching { return ZonedDateTime.parse(trimmed) }
        runCatching { return OffsetDateTime.parse(trimmed).toZonedDateTime() }
        runCatching { return Instant.parse(trimmed).atZone(zoneId) }
        runCatching { return JavaLocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zoneId) }
        runCatching { return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zoneId) }

        COMMON_DATE_TIME_PATTERNS.forEach { formatter ->
            runCatching { return JavaLocalDateTime.parse(trimmed, formatter).atZone(zoneId) }
            runCatching { return LocalDate.parse(trimmed, formatter).atStartOfDay(zoneId) }
        }
        return null
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

private val COMMON_DATE_TIME_PATTERNS = listOf(
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"),
    DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm"),
    DateTimeFormatter.ofPattern("uuuu-MM-dd"),
    DateTimeFormatter.ofPattern("uuuu/MM/dd"),
)
private val UTC_OFFSET_REGEX = Regex("""^UTC([+-]\d{1,2})(?::?(\d{2}))?$""", setOf(RegexOption.IGNORE_CASE))

private fun Duration.humanize(withSuffix: Boolean): String {
    val absoluteDuration = if (isNegative) negated() else this
    val totalSeconds = absoluteDuration.seconds
    if (totalSeconds <= 0) return "just now"

    val totalMinutes = absoluteDuration.toMinutes()
    val totalHours = absoluteDuration.toHours()
    val totalDays = absoluteDuration.toDays()
    val phrase = when {
        totalSeconds < 45 -> "a few seconds"
        totalSeconds < 90 -> "a minute"
        totalMinutes < 45 -> "${totalMinutes.coerceAtLeast(1)} minutes"
        totalMinutes < 90 -> "an hour"
        totalHours < 22 -> "${totalHours.coerceAtLeast(1)} hours"
        totalHours < 36 -> "a day"
        totalDays < 26 -> "${totalDays.coerceAtLeast(1)} days"
        totalDays < 45 -> "a month"
        totalDays < 320 -> "${(totalDays / 30.0).roundToInt().coerceAtLeast(1)} months"
        totalDays < 548 -> "a year"
        else -> "${(totalDays / 365.0).roundToInt().coerceAtLeast(1)} years"
    }
    if (!withSuffix) return phrase
    return if (isNegative) "$phrase ago" else "in $phrase"
}

private fun ZonedDateTime.formatTime(offsetSpec: String): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val target = UTC_OFFSET_REGEX.matchEntire(offsetSpec.trim())?.let { match ->
        val hours = match.groupValues[1].toIntOrNull() ?: return@let null
        val minutes = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return@let null
        ZoneOffset.ofHoursMinutes(hours, hours.signCompatibleMinutes(minutes))
    } ?: offset
    return withZoneSameInstant(target).format(formatter)
}

private fun ZonedDateTime.formatDate(): String {
    return format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}

private fun ZonedDateTime.formatWeekday(): String {
    return format(DateTimeFormatter.ofPattern("EEEE"))
}

private fun Int.signCompatibleMinutes(minutes: Int): Int {
    return when {
        this < 0 -> -minutes.absoluteValue
        this > 0 -> minutes.absoluteValue
        else -> minutes
    }
}

private fun ZonedDateTime.formatMomentStyle(rawFormat: String): String {
    val trimmed = rawFormat.trim()
    if (trimmed.isEmpty()) return format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    return when (trimmed) {
        "LT" -> format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        "L" -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
        "LL" -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        "LLL" -> format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT))
        "LLLL" -> format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT))
        else -> {
            val javaPattern = trimmed.toJavaDateTimePattern()
            runCatching { format(DateTimeFormatter.ofPattern(javaPattern)) }
                .getOrElse { format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
        }
    }
}

private fun String.toJavaDateTimePattern(): String {
    val replacements = listOf(
        "YYYY" to "uuuu",
        "YY" to "uu",
        "dddd" to "EEEE",
        "ddd" to "EEE",
        "DD" to "dd",
        "D" to "d",
        "MMMM" to "MMMM",
        "MMM" to "MMM",
        "MM" to "MM",
        "M" to "M",
        "HH" to "HH",
        "H" to "H",
        "hh" to "hh",
        "h" to "h",
        "mm" to "mm",
        "m" to "m",
        "ss" to "ss",
        "s" to "s",
        "A" to "a",
        "a" to "a",
        "ZZ" to "xx",
        "Z" to "XXX",
    )
    var value = this
    replacements.forEach { (momentToken, javaToken) ->
        value = value.replace(momentToken, javaToken)
    }
    return value
}

private fun kotlinx.datetime.LocalDateTime.toJavaLocalDateTime(): JavaLocalDateTime {
    return JavaLocalDateTime.of(year, monthNumber, day, hour, minute, second, nanosecond)
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
            "//" -> !isClosing
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
    val pickCache: MutableMap<String, Int> = linkedMapOf(),
    val outlets: MutableMap<String, String> = linkedMapOf(),
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
    val input: String = "",
    val original: String = "",
    val charVersion: String = "",
    val lastMessageId: String = "",
    val firstIncludedMessageId: String = "",
    val firstDisplayedMessageId: String = "",
    val lastSwipeId: String = "",
    val currentSwipeId: String = "",
    val maxPrompt: String = "",
    val defaultSystemPrompt: String = "",
    val systemPrompt: String = "",
    val generationType: String = "normal",
    val instructStoryStringPrefix: String = "",
    val instructStoryStringSuffix: String = "",
    val instructUserPrefix: String = "",
    val instructUserSuffix: String = "",
    val instructAssistantPrefix: String = "",
    val instructAssistantSuffix: String = "",
    val instructSystemPrefix: String = "",
    val instructSystemSuffix: String = "",
    val instructFirstAssistantPrefix: String = "",
    val instructLastAssistantPrefix: String = "",
    val instructStop: String = "",
    val instructUserFiller: String = "",
    val instructSystemInstructionPrefix: String = "",
    val instructFirstUserPrefix: String = "",
    val instructLastUserPrefix: String = "",
    val exampleSeparator: String = "",
    val chatStart: String = "",
    val isMobile: Boolean = true,
    val outlets: Map<String, String> = emptyMap(),
    val availableExtensions: Set<String> = emptySet(),
    val now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    val lastUserMessageCreatedAt: JavaLocalDateTime? = null,
) {
    fun hasExtension(name: String): Boolean {
        return name.trim().lowercase() in availableExtensions
    }

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
            val chatMessages = messages.filter { it.role != MessageRole.SYSTEM }
            val lastChatMessage = chatMessages.lastOrNull()?.toText().orEmpty()
            val lastUserMessageEntry = chatMessages.lastOrNull { it.role == MessageRole.USER }
            val lastAssistantMessageEntry = chatMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val lastUserMessage = lastUserMessageEntry?.toText().orEmpty()
            val lastAssistantMessage = lastAssistantMessageEntry?.toText().orEmpty()
            val groupValue = charName
            val notCharValue = userName
            val assistantSystemPrompt = ctx.assistant.systemPrompt
            val characterSystemPrompt = characterData?.systemPromptOverride.orEmpty()
            val activeSystemPrompt = characterSystemPrompt.ifBlank { assistantSystemPrompt }

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
                charPrompt = characterSystemPrompt,
                charInstruction = characterData?.postHistoryInstructions.orEmpty(),
                charDepthPrompt = characterData?.depthPrompt?.prompt.orEmpty(),
                creatorNotes = characterData?.creatorNotes.orEmpty(),
                exampleMessagesRaw = characterData?.exampleMessagesRaw.orEmpty(),
                lastChatMessage = lastChatMessage.ifBlank { lastAssistantMessage },
                lastUserMessage = lastUserMessage,
                lastAssistantMessage = lastAssistantMessage,
                modelName = ctx.model.displayName.ifBlank { template?.sourceName.orEmpty() },
                input = lastUserMessage,
                original = lastUserMessage,
                charVersion = characterData?.version.orEmpty(),
                lastMessageId = chatMessages.lastIndex.takeIf { it >= 0 }?.toString().orEmpty(),
                firstIncludedMessageId = chatMessages.firstOrNull()?.let { "0" }.orEmpty(),
                firstDisplayedMessageId = chatMessages.firstOrNull()?.let { "0" }.orEmpty(),
                maxPrompt = ctx.assistant.contextMessageSize.takeIf { it > 0 }?.toString().orEmpty(),
                defaultSystemPrompt = assistantSystemPrompt,
                systemPrompt = activeSystemPrompt,
                generationType = ctx.stGenerationType.trim().lowercase().ifBlank { "normal" },
                exampleSeparator = template?.newExampleChatPrompt.orEmpty(),
                chatStart = template?.newChatPrompt.orEmpty(),
                isMobile = true,
                outlets = ctx.stMacroState?.outlets.orEmpty(),
                now = ZonedDateTime.now(ZoneId.systemDefault()),
                lastUserMessageCreatedAt = lastUserMessageEntry?.createdAt?.toJavaLocalDateTime(),
            )
        }
    }
}
