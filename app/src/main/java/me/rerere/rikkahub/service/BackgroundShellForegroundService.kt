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
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import kotlin.uuid.Uuid

private const val TAG = "BackgroundShellFgs"

/**
 * 有后台命令在运行时把进程留在前台; 应用进程被回收时 PRoot 子进程会一起被杀掉。
 *
 * 命令本身由 [BackgroundShellManager] 持有，这里只提供前台服务的生命周期。
 */
class BackgroundShellForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.BACKGROUND_SHELL_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.BACKGROUND_SHELL_RELEASE"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val NOTIFICATION_ID = 2005

        fun acquire(context: Context, taskId: String, conversationId: Uuid): Boolean {
            val intent = Intent(context, BackgroundShellForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start background shell foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, taskId: String) {
            val intent = Intent(context, BackgroundShellForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_TASK_ID, taskId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release background shell foreground service", it)
            }
        }
    }

    // 任务 ID -> 会话 ID
    private val activeTasks = linkedMapOf<String, String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent, startId)
            ACTION_RELEASE -> release(intent, startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeTasks.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 命令不依赖前台服务才能运行：进程还在就继续跑，只是之后可能被系统回收
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        activeTasks.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun acquire(intent: Intent, startId: Int) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return stopSelf(startId)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopSelf(startId)
        activeTasks[taskId] = conversationId
        updateForegroundNotification()
    }

    private fun release(intent: Intent, startId: Int) {
        intent.getStringExtra(EXTRA_TASK_ID)?.let(activeTasks::remove)
        if (activeTasks.isEmpty()) {
            // 只在没有更新的启动请求排队时停止，原因见 ChatGenerationForegroundService.release
            stopSelf(startId)
        } else {
            updateForegroundNotification()
        }
    }

    private fun updateForegroundNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeTasks.clear()
            stopSelf()
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (activeTasks.size ==
                    1) {
                    "1 background command running"
                } else {
                    "${activeTasks.size} background commands running"
                }
            )
            .setContentIntent(getConversationPendingIntent(activeTasks.values.last()))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            conversationIntent(this, conversationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
