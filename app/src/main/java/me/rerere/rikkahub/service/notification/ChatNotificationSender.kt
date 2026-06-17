package me.rerere.rikkahub.service.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.lifecycle.sendNotification
import me.rerere.rikkahub.utils.lifecycle.cancelNotification
import kotlin.uuid.Uuid

/**
 * 聊天通知发送器：封装"生成完成"与"Live Update"两类通知的构建，从 ChatService 搬出以分离通知关注点。
 * 不持有会话状态——所需内容由调用方传入，使发送器只负责把内容翻译成系统通知。
 */
private const val LIVE_UPDATE_NOTIFICATION_ID = 2003

internal data class LiveUpdateNotificationIdentity(val tag: String, val id: Int)

internal data class LiveUpdateIntentSpec(
    val action: String,
    val requestCode: Int,
    val conversationId: String,
)

internal fun getLiveUpdateNotificationIdentity(conversationId: Uuid): LiveUpdateNotificationIdentity {
    return LiveUpdateNotificationIdentity(
        tag = conversationId.toString(),
        id = LIVE_UPDATE_NOTIFICATION_ID
    )
}

internal fun getLiveUpdateIntentSpec(conversationId: Uuid): LiveUpdateIntentSpec {
    val idString = conversationId.toString()
    return LiveUpdateIntentSpec(
        action = "me.rerere.rikkahub.route.CONVERSATION_NOTIFICATION.$idString",
        requestCode = idString.hashCode(),
        conversationId = idString,
    )
}

class ChatNotificationSender(private val context: Context) {

    fun sendGenerationDone(conversationId: Uuid, senderName: String, contentPreview: String) {
        // The per-conversation Live Update notification is cancelled separately by the generation
        // completion path (ChatService -> cancelLiveUpdate), keyed by this conversation's (tag, id);
        // this "done" notification is a distinct one-off, so nothing to cancel here.
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    fun sendLiveUpdate(conversationId: Uuid, senderName: String, parts: List<UIMessagePart>) {
        val strings = NotificationStrings(
            chipTool = context.getString(R.string.notification_live_update_chip_tool),
            chipThinking = context.getString(R.string.notification_live_update_chip_thinking),
            chipWriting = context.getString(R.string.notification_live_update_chip_writing),
            statusThinking = context.getString(R.string.notification_live_update_thinking),
            statusWriting = context.getString(R.string.notification_live_update_writing),
            statusDefault = context.getString(R.string.notification_live_update_title),
            tool = { name -> context.getString(R.string.notification_live_update_tool, name) },
        )

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts, strings)
        val identity = getLiveUpdateNotificationIdentity(conversationId)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationTag = identity.tag,
            notificationId = identity.id
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    fun cancelLiveUpdate(conversationId: Uuid) {
        val identity = getLiveUpdateNotificationIdentity(conversationId)
        context.cancelNotification(identity.tag, identity.id)
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val spec = getLiveUpdateIntentSpec(conversationId)
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = spec.action
            putExtra(RouteActivity.EXTRA_CONVERSATION_ID, spec.conversationId)
        }
        return PendingIntent.getActivity(
            context,
            spec.requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
