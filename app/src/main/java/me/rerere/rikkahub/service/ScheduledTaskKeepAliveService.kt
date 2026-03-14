package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.SCHEDULED_TASK_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.android.ext.android.inject

class ScheduledTaskKeepAliveService : Service() {
    companion object {
        private const val TAG = "ScheduledTaskKeepAlive"
        const val ACTION_START = "me.rerere.rikkahub.action.SCHEDULED_TASK_KEEP_ALIVE_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.SCHEDULED_TASK_KEEP_ALIVE_STOP"
        const val NOTIFICATION_ID = 2002
    }

    private val settingsStore: SettingsStore by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeSettingsJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!startForegroundSafely()) return START_NOT_STICKY
                observeKeepAliveSetting()
            }

            ACTION_STOP -> {
                disableKeepAliveAndStop(removeNotification = true)
            }

            null -> {
                if (!startForegroundSafely()) return START_NOT_STICKY
                observeKeepAliveSetting()
                serviceScope.launch {
                    if (!settingsStore.settingsFlowRaw.first().scheduledTaskKeepAliveEnabled) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            startForegroundCompat()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start scheduled task keep-alive service", e)
            disableKeepAliveAndStop(removeNotification = false)
            false
        }
    }

    private fun disableKeepAliveAndStop(removeNotification: Boolean) {
        observeSettingsJob?.cancel()
        serviceScope.launch {
            runCatching {
                settingsStore.update { it.copy(scheduledTaskKeepAliveEnabled = false) }
            }.onFailure { error ->
                Log.e(TAG, "Failed to disable scheduled task keep-alive service", error)
            }
            if (removeNotification) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }

    private fun observeKeepAliveSetting() {
        if (observeSettingsJob != null) return
        observeSettingsJob = serviceScope.launch {
            settingsStore.settingsFlow
                .map { it.scheduledTaskKeepAliveEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("openScheduledTaskSettings", true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, ScheduledTaskKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SCHEDULED_TASK_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_scheduled_task_keep_alive_title))
            .setContentText(getString(R.string.notification_scheduled_task_keep_alive_content))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.notification_scheduled_task_keep_alive_stop), stopPendingIntent)
            .build()
    }
}
