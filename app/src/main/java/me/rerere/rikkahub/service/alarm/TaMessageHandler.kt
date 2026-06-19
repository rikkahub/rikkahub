package me.rerere.rikkahub.service.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.TA_MESSAGE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.ai.buildMemoryPrompt
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.TaMessageAvatarUtil
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/**
 * 「同一路径」: 到点后的 I -> II -> III 三步统一收敛到 [handle] 这一个入口。
 *
 * - 触发源 (本期 [me.rerere.rikkahub.receiver.TaMessageAlarmReceiver]) 调用它;
 * - 未来若改用 WorkManager / 前台服务承载长时 LLM 调用, 其 doWork() 仍调用同一 handle(),
 *   保证「同一路径」不变。
 */
object TaMessageHandler : KoinComponent {
    private const val TAG = "TaMessageHandler"

    /**
     * ENTRY I 返回结果。
     *
     * @param reply LLM 回复文本，null 表示失败
     * @param conversationId 写入的会话 ID，null 表示未写入任何会话
     * @param conversation 完整会话对象，供 ENTRY III 直接使用（避免重复 DB 查询）
     */
    data class EntryResult(
        val reply: String?,
        val conversationId: Uuid?,
        val conversation: Conversation?,
    )

    suspend fun handle(context: Context, assistantId: Uuid) {
        val store = get<SettingsStore>()
        val settings = store.settingsFlowRaw.first()
        val assistant = settings.assistants.find { it.id == assistantId }
        if (assistant == null) {
            Log.w(TAG, "assistant not found: $assistantId")
            Logging.log(TAG, "assistant not found: $assistantId")
            return
        }

        // 一次性语义: nextTime 已为 null 说明本次闹钟已触发过, 跳过避免重复通知 (§15.2)
        if (assistant.taMessageNextTime == null) {
            Log.i(TAG, "assistant ${assistant.name} taMessageNextTime already cleared, skip")
            return
        }

        // =====【入口 I】后台静默给 LLM 发消息并获得回复 =====
        val result = requestTaMessageReply(context, assistant, settings)

        // =====【步骤 II】发送通知 =====
        val content = result.reply ?: "test"
        sendTaMessageNotification(context, assistant, content, result.conversationId)

        // =====【入口 III】再静默发送一次时间决策请求给 LLM 并获取回复 =====
        val newTime = requestTimeDecisionReply(context, assistant, result.conversation, settings)

        // =====【步骤 IV】根据 III 结果写入或清空 taMessageNextTime =====
        if (newTime != null) {
            store.update { s ->
                s.copy(
                    assistants = s.assistants.map { a ->
                        if (a.id == assistantId) a.copy(taMessageNextTime = newTime) else a
                    }
                )
            }
            TaMessageAlarmScheduler.schedule(context, assistantId, newTime)
        } else {
            runCatching {
                store.update { s ->
                    s.copy(
                        assistants = s.assistants.map { a ->
                            if (a.id == assistantId) a.copy(taMessageNextTime = null) else a
                        }
                    )
                }
            }.onFailure {
                Log.e(TAG, "clear taMessageNextTime after fire failed", it)
            }
        }
    }

    /**
     * 入口 I: 用 assistant.taMessagePrompt + 上下文调用 LLM, 返回回复文本及会话信息。
     *
     * 内部流程:
     * 1. 获取模型 (assistant.chatModelId → settings.chatModelId)
     * 2. 获取或创建会话
     * 3. 读取记忆 (如 enableMemory)
     * 4. 构造上下文消息列表
     * 5. 调用 LLM (非流式)
     * 6. 解析回复文本 → 构造 UIMessage → 写入会话窗口
     * 7. 返回 EntryResult
     */
    private suspend fun requestTaMessageReply(
        context: Context,
        assistant: Assistant,
        settings: Settings,
    ): EntryResult {
        // 1. 获取模型: assistant.chatModelId → settings.chatModelId
        val model = settings.findModelById(assistant.chatModelId, fallback = settings.chatModelId)
        if (model == null) {
            Log.w(TAG, "requestTaMessageReply: no model configured for assistant ${assistant.name}")
            Logging.log(TAG, "模型未配置，无法发送主动消息 (assistant=${assistant.name})")
            return EntryResult(null, null, null)
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            Log.w(TAG, "requestTaMessageReply: provider not found for model ${model.displayName}")
            Logging.log(TAG, "未找到模型 ${model.displayName} 的 provider")
            return EntryResult(null, null, null)
        }

        // 2. 获取或创建会话
        val conversationRepo = get<ConversationRepository>()
        var conversation: Conversation? = null
        var conversationId: Uuid? = null
        try {
            val recentConversations = conversationRepo.getRecentConversations(
                assistantId = assistant.id,
                limit = 1,
            )
            if (recentConversations.isNotEmpty()) {
                conversation = recentConversations.first()
                conversationId = conversation.id
            } else {
                // 自动创建新会话
                val newId = Uuid.random()
                val newConversation = Conversation.ofId(id = newId, assistantId = assistant.id)
                conversationRepo.insertConversation(newConversation)
                conversation = newConversation
                conversationId = newId
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestTaMessageReply: failed to get or create conversation", e)
            Logging.log(TAG, "获取或创建会话失败: ${e.message}")
            return EntryResult(null, null, null)
        }

        // 3. 读取记忆 (如 enableMemory，复用 buildMemoryPrompt 格式)
        val memoryBlock = if (assistant.enableMemory) {
            try {
                val memoryRepo = get<MemoryRepository>()
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepo.getGlobalMemories()
                } else {
                    memoryRepo.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    buildMemoryPrompt(memories)
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestTaMessageReply: failed to read memories", e)
                Logging.log(TAG, "读取记忆失败: ${e.message}")
                "" // 不影响主流程
            }
        } else {
            ""
        }

        // 4. 构造上下文消息列表 (按第一章顺序)
        val messages = buildList {
            // ① SYSTEM: assistant.systemPrompt + memory block
            val systemContent = buildString {
                append(assistant.systemPrompt)
                if (memoryBlock.isNotBlank()) {
                    appendLine()
                    append(memoryBlock)
                }
            }
            if (systemContent.isNotBlank()) {
                add(UIMessage.system(prompt = systemContent))
            }

            // ② USER/ASSISTANT 交替: 最近会话的完整对话历史
            if (conversation != null) {
                addAll(conversation.currentMessages)
            }

            // ③ USER: (系统提示：taMessagePrompt) + 当前系统时间
            val currentSystemTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toString()
            val userContent = buildString {
                append("（系统提示：${assistant.taMessagePrompt}）")
                appendLine()
                append("当前系统时间: $currentSystemTime")
            }
            add(UIMessage.user(prompt = userContent))
        }

        // 5. 调用 LLM (非流式)
        val providerManager = get<ProviderManager>()
        val providerHandler = providerManager.getProviderByType(provider)
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = ReasoningLevel.OFF,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )

        val resultChunk: MessageChunk = try {
            providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = params,
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestTaMessageReply: LLM call failed", e)
            Logging.log(TAG, "LLM 调用失败: ${e.message}")
            return EntryResult(null, conversationId, conversation)
        }

        // 6. 解析回复文本
        val replyText = resultChunk.choices.firstOrNull()?.message?.toText()?.trim() ?: ""
        if (replyText.isBlank()) {
            Log.w(TAG, "requestTaMessageReply: LLM returned empty response")
            Logging.log(TAG, "LLM 返回了空回复")
            return EntryResult(null, conversationId, conversation)
        }

        // 7. 构造 UIMessage 并写入会话窗口
        try {
            val message = UIMessage.assistant(prompt = replyText).copy(usage = resultChunk.usage)
            val chatService = get<ChatService>()
            chatService.injectAssistantMessage(conversationId!!, message)
        } catch (e: Exception) {
            Log.w(TAG, "requestTaMessageReply: failed to inject message into conversation", e)
            Logging.log(TAG, "写入会话失败: ${e.message}")
            return EntryResult(null, conversationId, conversation)
        }

        return EntryResult(reply = replyText, conversationId = conversationId, conversation = conversation)
    }

    /**
     * 入口 III: 直接复用已有的 [TaMessageDecisionHelper.requestTimeDecision]。
     *
     * @param conversation 来自 EntryResult.conversation，为 null 时直接返回 null（ENTRY I 失败）
     */
    private suspend fun requestTimeDecisionReply(
        context: Context,
        assistant: Assistant,
        conversation: Conversation?,
        settings: Settings,
    ): Long? {
        if (conversation == null) {
            Log.w(TAG, "requestTimeDecisionReply: conversation is null, skip")
            return null
        }
        return TaMessageDecisionHelper.requestTimeDecision(
            context = context,
            assistant = assistant,
            conversation = conversation,
            settings = settings,
        )
    }

    private suspend fun sendTaMessageNotification(
        context: Context,
        assistant: Assistant,
        content: String,
        conversationId: Uuid?,
    ) {
        val sizePx = (96 * context.resources.displayMetrics.density).toInt()
        val avatarBitmap = TaMessageAvatarUtil.renderAvatarBitmap(
            context = context,
            name = assistant.name,
            avatar = assistant.avatar,
            sizePx = sizePx
        )

        context.sendNotification(
            channelId = TA_MESSAGE_NOTIFICATION_CHANNEL_ID,
            notificationId = assistant.id.hashCode() // 每助手独立, 可各自更新/清除
        ) {
            title = assistant.name.ifBlank { context.getString(R.string.app_name) }
            this.content = content
            largeIcon = avatarBitmap
            smallIcon = R.drawable.small_icon
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            visibility = NotificationCompat.VISIBILITY_PUBLIC
            // 点击: 跳转到对应会话（若 conversationId 为 null 则仅启动应用主页）
            contentIntent = buildLaunchPendingIntent(context, conversationId)
        }
    }

    private fun buildLaunchPendingIntent(context: Context, conversationId: Uuid?): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (conversationId != null) {
            intent.putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
