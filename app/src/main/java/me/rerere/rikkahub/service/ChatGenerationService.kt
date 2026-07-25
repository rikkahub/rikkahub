package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.CHAT_GENERATION_NOTIFICATION_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val TAG = "ChatGenerationService"

class ChatGenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID) ?: return START_NOT_STICKY
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME).orEmpty()
        if (!startForegroundCompat(conversationId, senderName)) {
            stopSelfResult(startId)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(conversationId: String, senderName: String): Boolean {
        return try {
            val notification = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(getString(R.string.notification_live_update_title))
                .setContentText(senderName)
                .setContentIntent(buildChatPendingIntent(conversationId))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    CHAT_GENERATION_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(CHAT_GENERATION_NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start chat generation foreground service", e)
            false
        }
    }

    private fun buildChatPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val EXTRA_CONVERSATION_ID = "conversationId"
        private const val EXTRA_SENDER_NAME = "senderName"

        fun start(context: Context, conversationId: String, senderName: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ChatGenerationService::class.java).apply {
                        putExtra(EXTRA_CONVERSATION_ID, conversationId)
                        putExtra(EXTRA_SENDER_NAME, senderName)
                    },
                )
            }.onFailure {
                Log.e(TAG, "Unable to request chat generation foreground service", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ChatGenerationService::class.java))
            }.onFailure {
                Log.e(TAG, "Unable to stop the chat generation foreground service", it)
            }
        }
    }
}
