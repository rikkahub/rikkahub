package me.rerere.rikkahub.service.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.TA_MESSAGE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
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
 *
 * 本期仅实现步骤 II (发送内容固定为 "test" 的通知, largeIcon = 助手头像中心裁剪);
 * 入口 I / III 留占位, 未来实现后 II 代码零改动即可接入。
 */
object TaMessageHandler : KoinComponent {
    private const val TAG = "TaMessageHandler"

    suspend fun handle(context: Context, assistantId: Uuid) {
        val store = get<SettingsStore>()
        val settings = store.settingsFlowRaw.first()
        val assistant = settings.assistants.find { it.id == assistantId }
        if (assistant == null) {
            Log.w(TAG, "assistant not found: $assistantId")
            return
        }

        // 一次性语义: nextTime 已为 null 说明本次闹钟已触发过, 跳过避免重复通知 (§15.2)
        if (assistant.taMessageNextTime == null) {
            Log.i(TAG, "assistant ${assistant.name} taMessageNextTime already cleared, skip")
            return
        }

        // =====【入口 I】后台静默给 LLM 发消息并获得回复 (本期不实现, 留入口) =====
        val reply: String? = requestTaMessageReply(context, assistant)

        // =====【步骤 II】发送通知 (本期内容固定 "test"; 未来 reply 来自 I) =====
        val content = reply ?: "test"
        sendTaMessageNotification(context, assistant, content)

        // =====【入口 III】再静默发送一次时间决策请求给 LLM 并获取回复 (本期不实现, 留入口) =====
        val nextDecision: Long? = requestTimeDecisionReply(context, assistant)
        // 未来: nextDecision 决定下一次闹钟时间 -> TaMessageAlarmScheduler.schedule(...)

        // §15.2 修正1: 发送后立即清空 taMessageNextTime, 体现「一次性」语义;
        // 未来入口 III 实现后改为写入「下一次时间」。
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

    /** 入口 I (占位): 用 assistant.taMessagePrompt + 上下文调用 LLM, 返回回复文本 */
    private suspend fun requestTaMessageReply(context: Context, assistant: Assistant): String? {
        // TODO(后续实现): 经 Koin 取 ChatService/ProviderManager 等发起 LLM 请求
        return null
    }

    /** 入口 III (占位): 用 assistant.taMessageDecisionPrompt 调用 LLM, 返回下次时间戳(ms) */
    private suspend fun requestTimeDecisionReply(context: Context, assistant: Assistant): Long? {
        // TODO(后续实现): 调用 LLM 解析返回的时间戳, 调 scheduler.schedule(...) 注册下一次闹钟
        return null
    }

    private suspend fun sendTaMessageNotification(
        context: Context,
        assistant: Assistant,
        content: String
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
            largeIcon = avatarBitmap // NotificationConfig 新增字段
            smallIcon = R.drawable.small_icon
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            visibility = NotificationCompat.VISIBILITY_PUBLIC
            // 点击: 本期启动应用主界面; 未来跳转该助手会话 (见计划书第十一章 step 4 / §15.6)
            contentIntent = buildLaunchPendingIntent(context)
        }
    }

    private fun buildLaunchPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, RouteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
