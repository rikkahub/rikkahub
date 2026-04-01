package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.random.Random
import kotlin.uuid.Uuid

private val DEFAULT_SCHEDULED_TASK_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val userPersona: String = "",
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val enableRecentChatsReference: Boolean = false,
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val scheduledPromptTasks: List<ScheduledPromptTask> = emptyList(),
    val regexEnabled: Boolean = true,
    val regexes: List<AssistantRegex> = emptyList(),
    val thinkingBudget: Int? = 1024,
    val maxTokens: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val minP: Float? = null,
    val topK: Int? = null,
    val topA: Float? = null,
    val repetitionPenalty: Float? = null,
    val seed: Long? = null,
    val stopSequences: List<String> = emptyList(),
    val googleResponseMimeType: String = "",
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val stCompatScriptEnabled: Boolean = false,
    val stCompatScriptSource: String = "",
    val stCompatExtensionSettings: JsonObject = buildJsonObject { },
    val skillsEnabled: Boolean = false,
    val selectedSkills: Set<String> = emptySet(),
    val termuxNeedsApproval: Boolean = true,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val backgroundBlur: Float = 0f,
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val openAIReasoningEffort: String = "",
    val openAIVerbosity: String = "",
    val stCharacterData: SillyTavernCharacterData? = null,
)

fun Assistant.resolveConversationStarterMessages(
    random: Random = Random.Default,
): List<UIMessage> {
    val greetingCandidates = buildList {
        stCharacterData?.firstMessage
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::add)
        stCharacterData?.alternateGreetings
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(::add)
    }.distinct()
    if (greetingCandidates.isEmpty()) return presetMessages

    val selectedGreeting = greetingCandidates.random(random)
    if (presetMessages.isEmpty()) {
        return listOf(UIMessage.assistant(selectedGreeting))
    }

    var replacedGreeting = false
    val updatedPresetMessages = presetMessages.map { message ->
        if (!replacedGreeting && message.role == MessageRole.ASSISTANT) {
            replacedGreeting = true
            UIMessage.assistant(selectedGreeting)
        } else {
            message
        }
    }
    return if (replacedGreeting) {
        updatedPresetMessages
    } else {
        updatedPresetMessages + UIMessage.assistant(selectedGreeting)
    }
}

@Serializable
data class ScheduledPromptTask(
    val id: Uuid = Uuid.random(),
    val enabled: Boolean = true,
    val title: String = "",
    val prompt: String = "",
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val timeMinutesOfDay: Int = 9 * 60,
    val dayOfWeek: Int? = null, // 1..7, Monday..Sunday, only used when scheduleType == WEEKLY
    val assistantId: Uuid = DEFAULT_SCHEDULED_TASK_ASSISTANT_ID,
    val overrideModelId: Uuid? = null,
    val overrideLocalTools: List<LocalToolOption>? = null,
    val overrideMcpServers: Set<Uuid>? = null,
    val overrideEnableWebSearch: Boolean? = null,
    val overrideSearchServiceIndex: Int? = null,
    val overrideTermuxNeedsApproval: Boolean? = null,
    @Deprecated("Scheduled tasks now run in isolated snapshots and no longer use chat conversations")
    val conversationId: Uuid = Uuid.random(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val lastStatus: TaskRunStatus = TaskRunStatus.IDLE,
    val lastError: String = "",
    val lastRunId: Uuid? = null,
)

@Serializable
enum class ScheduleType {
    DAILY,
    WEEKLY,
}

@Serializable
enum class TaskRunStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
}

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    SYSTEM,
    USER,
    ASSISTANT,
}

@Serializable
enum class AssistantRegexSourceKind {
    MANUAL,
    ST_SCRIPT,
    ST_INLINE_PROMPT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
    val promptOnly: Boolean = false, // 是否仅影响发送给 LLM 的提示词
    val minDepth: Int? = null, // 最小深度：仅在倒数第 x 条及更早消息生效（包含 x）
    val maxDepth: Int? = null, // 最大深度：仅在倒数第 x 条消息内生效（包含 x）
    val trimStrings: List<String> = emptyList(),
    val runOnEdit: Boolean = true,
    val substituteRegex: Int = AssistantRegexSubstituteStrategy.NONE,
    val stPlacements: Set<Int> = emptySet(),
    val sourceKind: AssistantRegexSourceKind = AssistantRegexSourceKind.MANUAL,
    val sourceRef: String = "",
)

fun AssistantRegex.dedupKey(): String {
    return listOf(
        name,
        findRegex,
        replaceString,
        affectingScope.sortedBy { scope -> scope.name }.joinToString(","),
        visualOnly.toString(),
        promptOnly.toString(),
        minDepth?.toString().orEmpty(),
        maxDepth?.toString().orEmpty(),
        trimStrings.joinToString("\u0000"),
        runOnEdit.toString(),
        substituteRegex.toString(),
        stPlacements.sorted().joinToString(","),
    ).joinToString("|")
}

fun AssistantRegex.sourceLabel(): String? {
    return when (sourceKind) {
        AssistantRegexSourceKind.MANUAL -> null
        AssistantRegexSourceKind.ST_SCRIPT -> "SillyTavern Script"
        AssistantRegexSourceKind.ST_INLINE_PROMPT -> {
            val promptRef = sourceRef.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "SillyTavern Inline Prompt$promptRef"
        }
    }
}

enum class AssistantRegexApplyPhase {
    ACTUAL_MESSAGE, // 实际消息（会影响保存与后续上下文）
    VISUAL_ONLY, // 仅视觉渲染
    PROMPT_ONLY, // 仅发送给 LLM 的提示词
}

object AssistantRegexPlacement {
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6
}

object AssistantRegexSubstituteStrategy {
    const val NONE = 0
    const val RAW = 1
    const val ESCAPED = 2
}

fun List<UIMessage>.chatMessageDepthFromEndMap(): Map<Int, Int> {
    val chatIndices = mapIndexedNotNull { index, message ->
        if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) index else null
    }
    return chatIndices.mapIndexed { chatIndex, messageIndex ->
        messageIndex to (chatIndices.size - chatIndex)
    }.toMap()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    settings: Settings? = null,
    scope: AssistantAffectScope,
    phase: AssistantRegexApplyPhase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
    messageDepthFromEnd: Int? = null,
    placement: Int? = null,
    isEdit: Boolean = false,
    characterOverride: String? = null,
): String {
    val effectiveRegexes = settings?.effectiveRegexes(assistant)
        ?: assistant
            ?.takeIf { it.regexEnabled }
            ?.regexes
            .orEmpty()
    if (effectiveRegexes.isEmpty()) return this
    return effectiveRegexes.fold(this) { acc, regex ->
        if (
            regex.enabled &&
            regex.matchesPlacement(
                scope = scope,
                phase = phase,
                placement = placement,
                isEdit = isEdit,
            ) &&
            regex.matchesDepth(messageDepthFromEnd)
        ) {
            try {
                val pattern = regex.resolveFindRegex(
                    assistant = assistant,
                    settings = settings,
                    characterOverride = characterOverride,
                )
                val compiledRegex = Regex(pattern)
                val result = if (regex.requiresCustomReplacement()) {
                    compiledRegex.replace(acc) { matchResult ->
                        regex.buildReplacement(
                            matchResult = matchResult,
                            assistant = assistant,
                            settings = settings,
                            characterOverride = characterOverride,
                        )
                    }
                } else {
                    acc.replace(
                        regex = compiledRegex,
                        replacement = regex.replaceString,
                    )
                }
                result
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果正则表达式格式错误，返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

fun Settings.effectiveRegexes(assistant: Assistant?): List<AssistantRegex> {
    return (
        runtimeRegexes() +
            assistant
                ?.takeIf { it.regexEnabled }
                ?.regexes
                .orEmpty()
        )
        .distinctBy { it.dedupKey() }
}

private fun AssistantRegex.matchesPlacement(
    scope: AssistantAffectScope,
    phase: AssistantRegexApplyPhase,
    placement: Int?,
    isEdit: Boolean,
): Boolean {
    if (isEdit && !runOnEdit) return false
    if (stPlacements.isNotEmpty()) {
        if (placement == null || placement !in stPlacements) return false
        return when (phase) {
            AssistantRegexApplyPhase.ACTUAL_MESSAGE -> !visualOnly && !promptOnly
            AssistantRegexApplyPhase.VISUAL_ONLY -> visualOnly
            AssistantRegexApplyPhase.PROMPT_ONLY -> promptOnly
        }
    }
    if (!affectingScope.contains(scope)) return false
    return when (phase) {
        AssistantRegexApplyPhase.ACTUAL_MESSAGE -> !visualOnly && !promptOnly
        AssistantRegexApplyPhase.VISUAL_ONLY -> visualOnly
        AssistantRegexApplyPhase.PROMPT_ONLY -> promptOnly
    }
}

private fun AssistantRegex.matchesDepth(messageDepthFromEnd: Int?): Boolean {
    val depth = messageDepthFromEnd ?: return true
    val effectiveMinDepth = minDepth?.takeIf { it > 0 }
    val effectiveMaxDepth = maxDepth?.takeIf { it > 0 }
    if (effectiveMinDepth != null && depth < effectiveMinDepth) return false
    if (effectiveMaxDepth != null && depth > effectiveMaxDepth) return false
    return true
}

private fun AssistantRegex.resolveFindRegex(
    assistant: Assistant?,
    settings: Settings?,
    characterOverride: String?,
): String {
    return when (substituteRegex) {
        AssistantRegexSubstituteStrategy.RAW -> {
            substituteRegexMacros(
                text = findRegex,
                assistant = assistant,
                settings = settings,
                characterOverride = characterOverride,
                escapeReplacement = false,
            )
        }

        AssistantRegexSubstituteStrategy.ESCAPED -> {
            substituteRegexMacros(
                text = findRegex,
                assistant = assistant,
                settings = settings,
                characterOverride = characterOverride,
                escapeReplacement = true,
            )
        }

        else -> findRegex
    }
}

private fun AssistantRegex.requiresCustomReplacement(): Boolean {
    return trimStrings.isNotEmpty() ||
        stPlacements.isNotEmpty() ||
        substituteRegex != AssistantRegexSubstituteStrategy.NONE ||
        replaceString.contains("{{match}}", ignoreCase = true) ||
        replaceString.contains("\$<") ||
        replaceString.contains("\${")
}

private fun AssistantRegex.buildReplacement(
    matchResult: MatchResult,
    assistant: Assistant?,
    settings: Settings?,
    characterOverride: String?,
): String {
    val template = replaceString.replace(Regex("\\{\\{match}}", RegexOption.IGNORE_CASE), "\$0")
    val resolved = StringBuilder()
    var index = 0
    while (index < template.length) {
        val current = template[index]
        if (current != '$') {
            resolved.append(current)
            index++
            continue
        }

        if (index + 1 >= template.length) {
            resolved.append(current)
            index++
            continue
        }

        val next = template[index + 1]
        when {
            next == '$' -> {
                resolved.append('$')
                index += 2
            }

            next == '<' -> {
                val closing = template.indexOf('>', startIndex = index + 2)
                if (closing > index + 2) {
                    val groupName = template.substring(index + 2, closing)
                    val groupValue = matchResult.groups[groupName]?.value.orEmpty()
                    resolved.append(filterTrimStrings(groupValue, assistant, settings, characterOverride))
                    index = closing + 1
                } else {
                    resolved.append('$')
                    index++
                }
            }

            next == '{' -> {
                val closing = template.indexOf('}', startIndex = index + 2)
                if (closing > index + 2) {
                    val groupName = template.substring(index + 2, closing)
                    val groupValue = matchResult.groups[groupName]?.value.orEmpty()
                    resolved.append(filterTrimStrings(groupValue, assistant, settings, characterOverride))
                    index = closing + 1
                } else {
                    resolved.append('$')
                    index++
                }
            }

            next.isDigit() -> {
                var end = index + 2
                while (end < template.length && template[end].isDigit()) {
                    end++
                }
                val groupIndex = template.substring(index + 1, end).toIntOrNull()
                val groupValue = groupIndex
                    ?.let { resolvedGroupIndex -> matchResult.groups[resolvedGroupIndex]?.value }
                    .orEmpty()
                resolved.append(filterTrimStrings(groupValue, assistant, settings, characterOverride))
                index = end
            }

            else -> {
                resolved.append('$')
                index++
            }
        }
    }

    return substituteRegexMacros(
        text = resolved.toString(),
        assistant = assistant,
        settings = settings,
        characterOverride = characterOverride,
        escapeReplacement = false,
    )
}

private fun AssistantRegex.filterTrimStrings(
    value: String,
    assistant: Assistant?,
    settings: Settings?,
    characterOverride: String?,
): String {
    if (value.isEmpty() || trimStrings.isEmpty()) return value
    return trimStrings.fold(value) { acc, trimString ->
        val resolvedTrim = substituteRegexMacros(
            text = trimString,
            assistant = assistant,
            settings = settings,
            characterOverride = characterOverride,
            escapeReplacement = false,
        )
        if (resolvedTrim.isEmpty()) {
            acc
        } else {
            acc.replace(resolvedTrim, "")
        }
    }
}

private fun substituteRegexMacros(
    text: String,
    assistant: Assistant?,
    settings: Settings?,
    characterOverride: String?,
    escapeReplacement: Boolean,
): String {
    if (text.isEmpty()) return text
    val characterName = characterOverride
        ?.takeIf { it.isNotBlank() }
        ?: assistant?.stCharacterData?.name
            ?.takeIf { it.isNotBlank() }
        ?: assistant?.name
            ?.takeIf { it.isNotBlank() }
        ?: "char"
    val userName = settings?.effectiveUserName()
        ?.takeIf { it.isNotBlank() }
        ?: "user"
    val replacements = mapOf(
        "char" to characterName,
        "bot" to characterName,
        "name2" to characterName,
        "user" to userName,
        "name1" to userName,
    ).mapValues { (_, value) ->
        if (escapeReplacement) Regex.escape(value) else value
    }
    return replacements.entries.fold(text) { acc, (key, value) ->
        acc
            .replace("{{$key}}", value, ignoreCase = true)
            .replace("{${key}}", value, ignoreCase = true)
    }
}

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 兼容命名：ST 语义更接近角色定义区块之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 兼容命名：ST 语义更接近角色定义区块之后（最常用）

    @SerialName("author_note_top")
    AUTHOR_NOTE_TOP,        // Author's Note 之前（ST 对齐）

    @SerialName("author_note_bottom")
    AUTHOR_NOTE_BOTTOM,     // Author's Note 之后（ST 对齐）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 旧版兼容：对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 旧版兼容：最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）

    @SerialName("example_messages_top")
    EXAMPLE_MESSAGES_TOP,   // 示例消息之前（ST 对齐）

    @SerialName("example_messages_bottom")
    EXAMPLE_MESSAGES_BOTTOM, // 示例消息之后（ST 对齐）

    @SerialName("outlet")
    OUTLET,                  // ST Outlet，仅通过 {{outlet::name}} 等宏显式引用
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：SYSTEM / USER / ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发，作为系统提示词补充
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.SYSTEM,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.SYSTEM,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val secondaryKeywords: List<String> = emptyList(),
        val selective: Boolean = false,
        val selectiveLogic: Int = 0,
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val matchWholeWords: Boolean = false,
        val probability: Int? = null,
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
        val matchCharacterDescription: Boolean = false,
        val matchCharacterPersonality: Boolean = false,
        val matchPersonaDescription: Boolean = false,
        val matchScenario: Boolean = false,
        val matchCreatorNotes: Boolean = false,
        val matchCharacterDepthPrompt: Boolean = false,
        val stMetadata: Map<String, String> = emptyMap(),
    ) : PromptInjection()
}

fun InjectionPosition.normalizeForModeInjection(): InjectionPosition = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> InjectionPosition.BEFORE_SYSTEM_PROMPT
    InjectionPosition.AFTER_SYSTEM_PROMPT,
    InjectionPosition.AUTHOR_NOTE_TOP,
    InjectionPosition.AUTHOR_NOTE_BOTTOM,
    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AT_DEPTH,
    InjectionPosition.EXAMPLE_MESSAGES_TOP,
    InjectionPosition.EXAMPLE_MESSAGES_BOTTOM,
    InjectionPosition.OUTLET,
    -> InjectionPosition.AFTER_SYSTEM_PROMPT
}

fun PromptInjection.ModeInjection.normalizedForSystemPromptSupplement(): PromptInjection.ModeInjection = copy(
    position = position.normalizeForModeInjection(),
    injectDepth = 4,
    role = MessageRole.SYSTEM,
)

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val recursiveScanning: Boolean = false,
    val tokenBudget: Int? = null,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(
    context: String,
    triggerContext: LorebookTriggerContext = LorebookTriggerContext(recentMessagesText = context),
    globalSettings: LorebookGlobalSettings? = null,
): Boolean {
    if (!matchesTriggerKeywords(context, triggerContext, globalSettings)) return false
    return passesProbabilityCheck()
}

fun PromptInjection.RegexInjection.matchesTriggerKeywords(
    context: String,
    triggerContext: LorebookTriggerContext = LorebookTriggerContext(recentMessagesText = context),
    globalSettings: LorebookGlobalSettings? = null,
): Boolean {
    if (!enabled) return false
    if (!matchesGenerationType(triggerContext.generationType)) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false
    val caseSensitive = effectiveCaseSensitive(globalSettings)
    val matchWholeWords = effectiveMatchWholeWords(globalSettings)

    val haystacks = buildList {
        if (triggerContext.recentMessagesText.isNotBlank()) add(triggerContext.recentMessagesText)
        if (matchCharacterDescription && triggerContext.characterDescription.isNotBlank()) add(triggerContext.characterDescription)
        if (matchCharacterPersonality && triggerContext.characterPersonality.isNotBlank()) add(triggerContext.characterPersonality)
        if (matchPersonaDescription && triggerContext.personaDescription.isNotBlank()) add(triggerContext.personaDescription)
        if (matchScenario && triggerContext.scenario.isNotBlank()) add(triggerContext.scenario)
        if (matchCreatorNotes && triggerContext.creatorNotes.isNotBlank()) add(triggerContext.creatorNotes)
        if (matchCharacterDepthPrompt && triggerContext.characterDepthPrompt.isNotBlank()) add(triggerContext.characterDepthPrompt)
    }.ifEmpty { listOf(context) }

    val hasPrimaryMatch = keywords.any { keyword ->
        haystacks.any { haystack ->
            keywordMatches(
                keyword = keyword,
                context = haystack,
                useRegex = useRegex,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
            )
        }
    }
    if (!hasPrimaryMatch) return false

    if (!selective || secondaryKeywords.isEmpty()) {
        return true
    }

    val secondaryMatches = secondaryKeywords.map { keyword ->
        haystacks.any { haystack ->
            keywordMatches(
                keyword = keyword,
                context = haystack,
                useRegex = useRegex,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
            )
        }
    }

    val selectiveMatched = when (selectiveLogic) {
        1 -> !secondaryMatches.all { it } // NOT_ALL
        2 -> secondaryMatches.none { it } // NOT_ANY
        3 -> secondaryMatches.all { it } // AND_ALL
        else -> secondaryMatches.any { it } // AND_ANY
    }

    return selectiveMatched
}

fun PromptInjection.RegexInjection.matchScore(
    context: String,
    triggerContext: LorebookTriggerContext = LorebookTriggerContext(recentMessagesText = context),
    globalSettings: LorebookGlobalSettings? = null,
): Int {
    if (!enabled || !matchesGenerationType(triggerContext.generationType) || keywords.isEmpty()) {
        return 0
    }
    val caseSensitive = effectiveCaseSensitive(globalSettings)
    val matchWholeWords = effectiveMatchWholeWords(globalSettings)

    val haystacks = buildList {
        if (triggerContext.recentMessagesText.isNotBlank()) add(triggerContext.recentMessagesText)
        if (matchCharacterDescription && triggerContext.characterDescription.isNotBlank()) add(triggerContext.characterDescription)
        if (matchCharacterPersonality && triggerContext.characterPersonality.isNotBlank()) add(triggerContext.characterPersonality)
        if (matchPersonaDescription && triggerContext.personaDescription.isNotBlank()) add(triggerContext.personaDescription)
        if (matchScenario && triggerContext.scenario.isNotBlank()) add(triggerContext.scenario)
        if (matchCreatorNotes && triggerContext.creatorNotes.isNotBlank()) add(triggerContext.creatorNotes)
        if (matchCharacterDepthPrompt && triggerContext.characterDepthPrompt.isNotBlank()) add(triggerContext.characterDepthPrompt)
    }.ifEmpty { listOf(context) }

    val primaryScore = keywords.count { keyword ->
        haystacks.any { haystack ->
            keywordMatches(
                keyword = keyword,
                context = haystack,
                useRegex = useRegex,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
            )
        }
    }
    if (primaryScore == 0) return 0

    val secondaryScore = secondaryKeywords.count { keyword ->
        haystacks.any { haystack ->
            keywordMatches(
                keyword = keyword,
                context = haystack,
                useRegex = useRegex,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
            )
        }
    }
    if (secondaryKeywords.isEmpty()) return primaryScore

    return when (selectiveLogic) {
        0 -> primaryScore + secondaryScore
        3 -> if (secondaryScore == secondaryKeywords.size) primaryScore + secondaryScore else primaryScore
        else -> primaryScore
    }
}

fun PromptInjection.RegexInjection.passesProbabilityCheck(forceSuccess: Boolean = false): Boolean {
    if (forceSuccess) return true
    val extension = stExtension()
    val useProbability = extension.useProbability ?: true
    if (!useProbability) return true
    val chance = probability ?: return true
    if (chance >= 100) return true
    if (chance <= 0) return false
    return kotlin.random.Random.nextInt(100) < chance
}

fun PromptInjection.RegexInjection.matchesGenerationType(generationType: String): Boolean {
    val normalizedType = generationType.trim().lowercase().ifBlank { "normal" }
    val triggers = stExtension().triggers
        .mapNotNull { trigger ->
            trigger.trim().lowercase().takeIf { it.isNotBlank() }
        }
        .distinct()
    return triggers.isEmpty() || normalizedType in triggers
}

private fun keywordMatches(
    keyword: String,
    context: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    matchWholeWords: Boolean,
): Boolean {
    if (keyword.isBlank() || context.isBlank()) return false

    val regexFromSlash = parseSlashRegex(keyword, caseSensitive)
    if (useRegex || regexFromSlash != null) {
        return runCatching {
            val regex = regexFromSlash ?: Regex(
                keyword,
                if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            )
            regex.containsMatchIn(context)
        }.getOrDefault(false)
    }

    return if (matchWholeWords) {
        val escaped = Regex.escape(keyword)
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex("""(?:^|[^\p{L}\p{N}_])$escaped(?:$|[^\p{L}\p{N}_])""", options).containsMatchIn(context)
    } else {
        context.contains(keyword, ignoreCase = !caseSensitive)
    }
}

private fun parseSlashRegex(input: String, caseSensitive: Boolean): Regex? {
    if (!input.startsWith('/') || input.length < 2) return null

    var escaped = false
    var closingSlashIndex = -1
    for (index in 1 until input.length) {
        val char = input[index]
        if (escaped) {
            escaped = false
            continue
        }
        if (char == '\\') {
            escaped = true
            continue
        }
        if (char == '/') {
            closingSlashIndex = index
        }
    }

    if (closingSlashIndex <= 0) return null

    val pattern = input.substring(1, closingSlashIndex)
    val flags = input.substring(closingSlashIndex + 1)
    val options = mutableSetOf<RegexOption>()
    if (!caseSensitive && 'i' !in flags) {
        options += RegexOption.IGNORE_CASE
    }
    if ('i' in flags) options += RegexOption.IGNORE_CASE
    if ('m' in flags) options += RegexOption.MULTILINE
    if ('s' in flags) options += RegexOption.DOT_MATCHES_ALL
    return runCatching { Regex(pattern, options) }.getOrNull()
}

private fun PromptInjection.RegexInjection.effectiveCaseSensitive(
    globalSettings: LorebookGlobalSettings?,
): Boolean {
    return caseSensitive || globalSettings?.caseSensitive == true
}

private fun PromptInjection.RegexInjection.effectiveMatchWholeWords(
    globalSettings: LorebookGlobalSettings?,
): Boolean {
    return matchWholeWords || globalSettings?.matchWholeWords == true
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int,
    includeNames: Boolean = false,
    userName: String = "User",
    assistantName: String = "Assistant",
): String {
    return messages
        .takeLast(scanDepth.coerceAtLeast(0))
        .joinToString("\n") { message ->
            if (!includeNames) {
                return@joinToString message.toText()
            }
            val speaker = when (message.role) {
                MessageRole.USER -> userName
                MessageRole.ASSISTANT -> assistantName
                else -> null
            }
            if (speaker == null) {
                message.toText()
            } else {
                "$speaker: ${message.toText()}"
            }
        }
}

/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
