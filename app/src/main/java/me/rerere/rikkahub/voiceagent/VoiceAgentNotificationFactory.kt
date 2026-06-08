package me.rerere.rikkahub.voiceagent

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_AGENT_NOTIFICATION_CHANNEL_ID

class VoiceAgentNotificationFactory(
    private val context: Context,
) {
    fun activeNotification(conversationId: String, state: VoiceAgentUiState): Notification {
        return NotificationCompat.Builder(context, VOICE_AGENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("RikkaHub Voice Agent")
            .setContentText(state.notificationText())
            .setContentIntent(openVoiceAgentPendingIntent(conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "End", endPendingIntent())
            .build()
    }

    private fun openVoiceAgentPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID, conversationId)
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun endPendingIntent(): PendingIntent {
        val intent = Intent(context, VoiceAgentCallService::class.java)
            .setAction(VoiceAgentCallContract.ACTION_END)
        return PendingIntent.getService(
            context,
            VoiceAgentCallContract.NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

private fun VoiceAgentUiState.notificationText(): String =
    when (val callStatus = call) {
        VoiceCallStatus.ForegroundStarting -> "Starting call runtime"
        VoiceCallStatus.BackgroundCapable -> when (session) {
            VoiceSessionStatus.Connected -> "Active - background ready"
            VoiceSessionStatus.Reconnecting -> "Reconnecting"
            else -> "Starting"
        }
        is VoiceCallStatus.Degraded -> "Degraded: ${callStatus.message}"
        VoiceCallStatus.Ending -> "Ending"
        VoiceCallStatus.Ended -> "Ended"
        VoiceCallStatus.Idle -> when (session) {
            is VoiceSessionStatus.Error -> "Error: ${session.message}"
            else -> "Starting"
        }
    }
