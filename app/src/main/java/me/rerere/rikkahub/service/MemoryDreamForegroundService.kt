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
import me.rerere.rikkahub.MEMORY_DREAM_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.MemoryDreamKeepAlive
import kotlin.uuid.Uuid

private const val TAG = "MemoryDreamFgs"

/**
 * 后台整理记忆（做梦）期间把进程留在前台，应用被切走后模型调用和写入仍能跑完。
 * 应用不可见时结束的对话，整理前等空闲的那段时间也占着，否则进程可能等不到计时走完就被冻结。
 *
 * 整理本身由 [me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler] 驱动，这里只提供前台服务的生命周期。
 */
class MemoryDreamForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.MEMORY_DREAM_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.MEMORY_DREAM_RELEASE"
        private const val EXTRA_HOLD_ID = "hold_id"

        const val NOTIFICATION_ID = 2004

        fun acquire(context: Context, holdId: String): Boolean {
            val intent = Intent(context, MemoryDreamForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_HOLD_ID, holdId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                // 应用在后台且没有别的前台服务时系统不允许启动，这次就不保活
                Log.w(TAG, "Unable to start memory dream foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, holdId: String) {
            val intent = Intent(context, MemoryDreamForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_HOLD_ID, holdId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release memory dream foreground service", it)
            }
        }
    }

    private val holds = mutableSetOf<String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent, startId)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        holds.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 整理不依赖前台服务：进程还在就继续跑，被回收了进度不会推进，下次启动会重新整理
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        holds.clear()
        stopService()
    }

    private fun acquire(intent: Intent) {
        val holdId = intent.getStringExtra(EXTRA_HOLD_ID) ?: return stopService()
        holds += holdId
        enterForeground()
    }

    private fun release(intent: Intent, startId: Int) {
        intent.getStringExtra(EXTRA_HOLD_ID)?.let(holds::remove)
        // 带上 startId：如果又有一次占用已经发出、只是还没送到，这里就不会停掉服务。
        // 直接停的话，系统会因为那次 startForegroundService 没等到 startForeground 而让应用崩溃
        if (holds.isEmpty()) stopSelf(startId)
    }

    private fun enterForeground() {
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
            holds.clear()
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
        NotificationCompat.Builder(this, MEMORY_DREAM_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("Dreaming…")
            .setContentText("Turning your chats into memories")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, RouteActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
}

/** 用前台服务实现的保活：每次占用对应一个 hold，全部释放后服务退出 */
fun memoryDreamKeepAlive(context: Context) = MemoryDreamKeepAlive {
    val holdId = Uuid.random().toString()
    val started = MemoryDreamForegroundService.acquire(context, holdId)
    AutoCloseable { if (started) MemoryDreamForegroundService.release(context, holdId) }
}
