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
import me.rerere.rikkahub.service.GenerationForegroundService
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

/**
 * 聊天通知发送器：封装"生成完成"与"Live Update"两类通知的构建，从 ChatService 搬出以分离通知关注点。
 * 不持有会话状态——所需内容由调用方传入，使发送器只负责把内容翻译成系统通知。
 */
class ChatNotificationSender(private val context: Context) {

    fun sendGenerationDone(conversationId: Uuid, senderName: String, contentPreview: String) {
        // 不在此取消 Live Update 通知：它现在是前台服务持有的同一条常驻通知，由 FGS 在 1->0 边移除。
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

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId()
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

    // 单一常驻通知：复用前台服务的通知 ID，让 Live Update 富文本就地更新 GenerationForegroundService
    // 启动时占位的同一条通知，而不是再发一条 per-conversation 的常驻通知（否则后台生成时会出现两条
    // 常驻通知）。通知的取消由前台服务在 1->0 边 stopForeground(STOP_FOREGROUND_REMOVE) 唯一负责，
    // 因此多会话并发时单个会话结束不会误删这条共享通知。
    private fun getLiveUpdateNotificationId(): Int {
        return GenerationForegroundService.NOTIFICATION_ID
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
