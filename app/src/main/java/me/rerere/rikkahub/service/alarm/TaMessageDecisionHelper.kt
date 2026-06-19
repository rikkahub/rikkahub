package me.rerere.rikkahub.service.alarm

import android.content.Context
import android.util.Log
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private const val TAG = "TaMessageDecisionHelper"

/**
 * 系统内置的「决策时间系统内置提示词」。
 *
 * 固定字符串常量，不含运行时占位符。用于约束 LLM 输出的 JSON 格式。
 * 该内容拼接在用户设置的 [Assistant.taMessageDecisionPrompt] 之后，
 * 共同作为 system 消息发送给 LLM。
 */
private const val SYSTEM_BUILTIN_DECISION_PROMPT = """
【输出要求】
  你必须严格以 JSON 格式输出，不要包含任何额外文字：
  {
    "should_update": true或false,
    "next_care_time": "ISO 8601格式的完整时间字符串，必须包含时区信息，如2026-06-12T10:00:00+08:00"
  }
"""

/**
 * 「Ta 的来信」静默决策时间。
 *
 * 在助手每次回复后（开启 Ta 的来信时）自动调用，静默请求 LLM 做时间决策。
 * 不写入窗口，不统计 token。
 *
 * 使用方式（由 ChatService 在 handleMessageComplete().onSuccess 中调用）：
 * ```
 * TaMessageDecisionHelper.onAfterReply(
 *     context = context,
 *     assistant = assistant,
 *     conversation = finalConversation,
 *     settings = settings,
 * )
 * ```
 */
object TaMessageDecisionHelper : KoinComponent {

    /**
     * 静默调用 LLM 做时间决策。不会写入窗口，不统计 token。
     *
     * @param context Android Context（用于 Provider 等）
     * @param assistant 当前助手
     * @param conversation 当前对话（含完整消息列表）
     * @param settings 当前全局设置（含模型列表）
     * @return 新的 taMessageNextTime（毫秒时间戳），null 表示无需修改
     */
    suspend fun requestTimeDecision(
        context: Context,
        assistant: Assistant,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): Long? {
        // ===== 组装上下文消息（严格遵守顺序） =====
        val messages = buildList {
            // ---- I. system 身份 → 1 条消息 ----
            // 拼接：用户设置的决策时间提示词 + 系统内置 JSON 格式要求
            val systemContent = buildString {
                append(assistant.taMessageDecisionPrompt)
                append("\n\n")
                append(SYSTEM_BUILTIN_DECISION_PROMPT)
            }
            add(UIMessage.system(prompt = systemContent))

            // ---- II. user 身份 → 1 或 2 条消息 ----
            // (1) 助手设定（必有）
            add(
                UIMessage.user(
                    prompt = "以下为供你参考的助手设定:\n${assistant.systemPrompt}"
                )
            )

            // (2) 助手记忆（仅当 enableMemory == true 时有）
            if (assistant.enableMemory) {
                val memoryRepo = get<MemoryRepository>()
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepo.getGlobalMemories()
                } else {
                    memoryRepo.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    val memoryContent = memories.joinToString("\n") { "- ${it.content}" }
                    add(
                        UIMessage.user(
                            prompt = "以下为供你参考的助手记忆\n$memoryContent"
                        )
                    )
                }
            }

            // ---- III. 对话历史 → 交替的 user/assistant 消息 ----
            addAll(conversation.currentMessages)

            // ---- IV. (末尾) user 身份 → 当前时间与已设定时间 ----
            val currentNextTimeStr = if (assistant.taMessageNextTime != null) {
                Instant.fromEpochMilliseconds(assistant.taMessageNextTime!!)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .toString()
            } else {
                "未设置"
            }
            val currentSystemTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toString()
            add(
                UIMessage.user(
                    prompt = "当前已设定的下次主动关怀时间: $currentNextTimeStr\n当前系统时间: $currentSystemTime"
                )
            )
        }

        // ===== 记录发送给 LLM 的消息（内置日志 & logcat） =====
        val messagesSummary = messages.joinToString("\n---\n") { msg ->
            val role = msg.role.name.padEnd(10)
            val text = msg.toText().take(300).replace("\n", "\\n")
            "[$role] $text"
        }
        Log.i(TAG, "发送给 LLM 的消息 (${messages.size} 条):\n$messagesSummary")
        Logging.log(TAG, "请求 LLM 决策时间: 共 ${messages.size} 条消息\n$messagesSummary")

        // ===== 获取模型（与标题/建议生成模型一致） =====
        val model = settings.findModelById(settings.taMessageModelId, fallback = settings.fastModelId)
            ?: return null
        val provider = model.findProvider(settings.providers)
            ?: return null

        // ===== 非流式调用 LLM =====
        val providerManager = get<ProviderManager>()
        val providerHandler = providerManager.getProviderByType(provider)

        val result = runCatching {
            providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = backgroundTextGenerationParams(model),
            )
        }.onFailure { error ->
            Log.w(TAG, "requestTimeDecision: LLM call failed", error)
            Logging.log(TAG, "LLM 调用失败: $error")
        }.getOrNull() ?: return null

        // ===== 解析回复 =====
        val text = result.choices.firstOrNull()?.message?.toText()?.trim() ?: ""
        if (text.isBlank()) {
            Log.w(TAG, "requestTimeDecision: empty response")
            Logging.log(TAG, "LLM 返回了空回复")
            return null
        }

        Log.i(TAG, "LLM回复原始内容:\n$text")
        Logging.log(TAG, "LLM 原始回复:\n$text")
        val decision = parseTimeDecision(text)
        if (decision != null) {
            val decisionTimeLocal = Instant.fromEpochMilliseconds(decision)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            Log.i(TAG, "决策结果: 将 taMessageNextTime 更新为 $decision ($decisionTimeLocal)")
            Logging.log(TAG, "决策结果: 更新 taMessageNextTime = $decision ($decisionTimeLocal)")
        } else {
            Log.i(TAG, "决策结果: 无需修改（should_update=false 或解析失败）")
            Logging.log(TAG, "决策结果: 无需修改时间")
        }
        return decision
    }

    /**
     * 在每次助手回复后调用（仅当 taMessageEnabled == true 时）。
     *
     * 负责：组装上下文 → 调 LLM 决策 → 修改 taMessageNextTime → 同步闹钟。
     */
    suspend fun onAfterReply(
        context: Context,
        assistant: Assistant,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ) {
        val msg = "开始处理助手「${assistant.name}」(${assistant.id}) 的时间决策"
        Log.i(TAG, "onAfterReply: $msg")
        Logging.log(TAG, "[Ta的来信] $msg")
        val newTime = requestTimeDecision(
            context = context,
            assistant = assistant,
            conversation = conversation,
            settings = settings,
        )
        if (newTime != null) {
            val successMsg = "更新 taMessageNextTime 为 $newTime，同步闹钟"
            Log.i(TAG, "onAfterReply: $successMsg")
            Logging.log(TAG, "[Ta的来信] $successMsg")
            val store = get<SettingsStore>()
            store.update { s ->
                s.copy(
                    assistants = s.assistants.map { a ->
                        if (a.id == assistant.id) a.copy(taMessageNextTime = newTime) else a
                    }
                )
            }
            // 同步更新 AlarmManager
            TaMessageAlarmScheduler.schedule(context, assistant.id, newTime)
        } else {
            Log.i(TAG, "onAfterReply: 无需修改时间")
            Logging.log(TAG, "[Ta的来信] 无需修改时间")
        }
    }

    /**
     * 解析 LLM 返回的 JSON 时间决策字符串。
     *
     * 预期格式：
     * ```json
     * {"should_update": true, "next_care_time": "2026-06-12T10:00:00"}
     * ```
     * 或
     * ```json
     * {"should_update": false}
     * ```
     *
     * @param jsonText LLM 返回的原始文本（应仅包含 JSON）
     * @return 新的时间戳（毫秒），null 表示无需修改或解析失败
     */
    internal fun parseTimeDecision(rawText: String): Long? {
        // 清理可能的 markdown 代码围栏 (```json ... ``` 或 ``` ... ```)
        val jsonText = rawText
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val json = try {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "parseTimeDecision: failed to parse JSON", e)
            Logging.log(TAG, "JSON 解析失败: ${e.message}")
            return null
        }

        val shouldUpdate = json["should_update"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!shouldUpdate) return null

        val isoString = json["next_care_time"]?.jsonPrimitive?.contentOrNull
        if (isoString.isNullOrBlank()) {
            Log.w(TAG, "parseTimeDecision: should_update=true but next_care_time is missing or empty")
            Logging.log(TAG, "should_update=true 但 next_care_time 缺失或为空")
            return null
        }

        return try {
            // 先尝试带时区的完整 ISO 8601（如 "2026-06-19T12:28:43Z" 或 "2026-06-19T12:28:43+08:00"）
            Instant.parse(isoString).toEpochMilliseconds()
        } catch (_: Exception) {
            // 若失败，尝试作为无时区的本地时间解析，用当前系统时区补全
            try {
                val localDt = LocalDateTime.parse(isoString)
                val instant = localDt.toInstant(TimeZone.currentSystemDefault())
                Log.i(TAG, "parseTimeDecision: 使用本地时间 $isoString + 系统时区 = $instant")
                Logging.log(TAG, "日期解析: 无时区时间 $isoString 使用系统时区补全 = $instant")
                instant.toEpochMilliseconds()
            } catch (e2: Exception) {
                Log.w(TAG, "parseTimeDecision: failed to parse date: $isoString", e2)
                Logging.log(TAG, "日期解析失败: $isoString, ${e2.message}")
                null
            }
        }
    }

    /**
     * 后台文本生成参数（与 generateTitle / generateSuggestion 一致）。
     * 不含 temperature、topP、tools 等额外参数。
     */
    private fun backgroundTextGenerationParams(model: Model): TextGenerationParams =
        TextGenerationParams(
            model = model,
            reasoningLevel = ReasoningLevel.OFF,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )
}
