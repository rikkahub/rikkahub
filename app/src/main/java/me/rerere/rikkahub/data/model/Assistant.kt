package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
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
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val scheduledPromptTasks: List<ScheduledPromptTask> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val thinkingBudget: Int? = 1024,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val localToolPrompts: Map<String, String> = emptyMap(),
    val skillsEnabled: Boolean = false,
    val selectedSkills: Set<String> = emptySet(),
    val termuxNeedsApproval: Boolean = true,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val backgroundBlur: Float = 0f,
    val messageInjectionTemplate: MessageInjectionTemplate = MessageInjectionTemplate.default(),
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val openAIReasoningEffort: String = "",
    val stPromptTemplate: SillyTavernPromptTemplate? = null,
    val stCharacterData: SillyTavernCharacterData? = null,
)

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
)

enum class AssistantRegexApplyPhase {
    ACTUAL_MESSAGE, // 实际消息（会影响保存与后续上下文）
    VISUAL_ONLY, // 仅视觉渲染
    PROMPT_ONLY, // 仅发送给 LLM 的提示词
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
): String {
    val effectiveRegexes = settings?.effectiveRegexes(assistant) ?: assistant?.regexes.orEmpty()
    if (effectiveRegexes.isEmpty()) return this
    return effectiveRegexes.fold(this) { acc, regex ->
        if (
            regex.enabled &&
            regex.matchesPhase(phase) &&
            regex.affectingScope.contains(scope) &&
            regex.matchesDepth(messageDepthFromEnd)
        ) {
            try {
                val result = acc.replace(
                    regex = Regex(regex.findRegex),
                    replacement = regex.replaceString,
                )
                // println("Regex: ${regex.findRegex} -> ${result}")
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
    return (regexes + assistant?.regexes.orEmpty())
        .distinctBy { regex ->
            listOf(
                regex.name,
                regex.findRegex,
                regex.replaceString,
                regex.affectingScope.sortedBy { scope -> scope.name }.joinToString(","),
                regex.visualOnly.toString(),
                regex.promptOnly.toString(),
                regex.minDepth?.toString().orEmpty(),
                regex.maxDepth?.toString().orEmpty(),
            ).joinToString("|")
        }
}

private fun AssistantRegex.matchesPhase(phase: AssistantRegexApplyPhase): Boolean {
    if (visualOnly && promptOnly) return false
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

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
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
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
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
        override val role: MessageRole = MessageRole.USER,
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
        override val role: MessageRole = MessageRole.USER,
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

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
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
    triggerContext: LorebookTriggerContext = LorebookTriggerContext(recentMessagesText = context)
): Boolean {
    if (!enabled) return false
    if (constantActive) return passesProbabilityCheck()
    if (keywords.isEmpty()) return false

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
        return passesProbabilityCheck()
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

    return selectiveMatched && passesProbabilityCheck()
}

private fun PromptInjection.RegexInjection.passesProbabilityCheck(): Boolean {
    val chance = probability ?: return true
    if (chance >= 100) return true
    if (chance <= 0) return false
    return kotlin.random.Random.nextInt(100) < chance
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

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
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
