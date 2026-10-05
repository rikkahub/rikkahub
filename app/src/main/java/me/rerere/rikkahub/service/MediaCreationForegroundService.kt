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
import me.rerere.rikkahub.MEDIA_CREATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import kotlin.uuid.Uuid

private const val TAG = "MediaCreationFgs"

/**
 * 有媒体生成任务在跑时把进程留在前台，界面被隐藏后上传、轮询和下载仍能继续。
 *
 * 任务本身由 [MediaCreationService] 持有，这里只提供前台服务的生命周期。
 */
class MediaCreationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.MEDIA_CREATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.MEDIA_CREATION_RELEASE"
        private const val EXTRA_RECORD_ID = "record_id"
        private const val EXTRA_SESSION_ID = "session_id"

        const val EXTRA_MEDIA_SESSION_ID = "mediaSessionId"
        const val NOTIFICATION_ID = 2003

        fun acquire(context: Context, recordId: Uuid, sessionId: Uuid): Boolean {
            val intent = Intent(context, MediaCreationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_RECORD_ID, recordId.toString())
                putExtra(EXTRA_SESSION_ID, sessionId.toString())
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start media creation foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, recordId: Uuid) {
            val intent = Intent(context, MediaCreationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_RECORD_ID, recordId.toString())
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release media creation foreground service", it)
            }
        }

        /** 点击通知后打开对应的媒体创作会话。 */
        fun sessionPendingIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MEDIA_SESSION_ID, sessionId)
            }
            return PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    // 记录 ID -> 会话 ID
    private val activeRecords = linkedMapOf<String, String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeRecords.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 任务不依赖前台服务才能恢复：进程还在就继续跑，被回收了下次启动会按任务 ID 接着查询
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        activeRecords.clear()
        stopService()
    }

    private fun acquire(intent: Intent) {
        val recordId = intent.getStringExtra(EXTRA_RECORD_ID) ?: return stopService()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return stopService()
        activeRecords[recordId] = sessionId
        updateForegroundNotification()
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_RECORD_ID)?.let(activeRecords::remove)
        if (activeRecords.isEmpty()) {
            stopService()
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
            activeRecords.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, MEDIA_CREATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.media_creation_title))
            .setContentText(getString(R.string.media_creation_notification_running, activeRecords.size))
            .setContentIntent(sessionPendingIntent(this, activeRecords.values.last()))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
}
