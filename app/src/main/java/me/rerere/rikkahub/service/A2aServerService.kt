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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.A2A_SERVER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.A2aServerManager
import org.koin.android.ext.android.inject

private const val TAG = "A2aServerService"

class A2aServerService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.A2A_SERVER_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.A2A_SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_LOCALHOST_ONLY = "localhost_only"

        // MUST be unique across every foreground-service notification id (2001 WebServerService,
        // 2002 GenerationForegroundService, 2003 the chat live-update). Two foreground services that
        // share one id collide: while both are foreground, the one calling startForeground last stamps
        // the notification content, and a stopForeground(REMOVE) from one CANNOT remove a notification
        // the other FGS still backs — it lingers with the stale content. Sharing 2002 with the
        // generation FGS left this service's notification stuck reading "Generating response..." after
        // a turn ended (it never re-posts its own running notification when idle).
        const val NOTIFICATION_ID = 2004
    }

    private val a2aServerManager: A2aServerManager by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            if (shouldLogServiceError(e)) Log.e(TAG, "A2aServerService coroutine failed", e)
        }
    )
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 9000)
                val localhostOnly = intent.getBooleanExtra(EXTRA_LOCALHOST_ONLY, true)
                startForegroundCompat()
                startObservingState()
                a2aServerManager.start(port = port, localhostOnly = localhostOnly)
            }

            ACTION_STOP -> {
                a2aServerManager.stop()
                serviceScope.launch {
                    settingsStore.update { it.copy(a2aEnabled = false) }
                }
            }

            null -> {
                startForegroundCompat()
                serviceScope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (settings.a2aEnabled) {
                        startObservingState()
                        a2aServerManager.start(
                            port = settings.a2aServerPort,
                            localhostOnly = settings.a2aServerLocalhostOnly,
                        )
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
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
                buildStartingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildStartingNotification())
        }
    }

    private fun startObservingState() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            var wasRunning = false
            a2aServerManager.state.collect { state ->
                when (
                    a2aServiceLifecycleAction(
                        wasRunning = wasRunning,
                        isRunning = state.isRunning,
                        isLoading = state.isLoading,
                        hasError = state.error != null,
                    )
                ) {
                    A2aServiceLifecycleAction.RUNNING -> {
                        val firstRun = !wasRunning
                        wasRunning = true
                        // Persist the enable flag only once the server is CONFIRMED running, so
                        // app-open autostart never resurrects a start that actually failed. The
                        // service is the single writer of a2aEnabled (success here, false on
                        // failure/stop) — the UI does not write it optimistically.
                        if (firstRun) settingsStore.update { it.copy(a2aEnabled = true) }
                        updateNotification(buildRunningNotification(state.url ?: "http://localhost:${state.port}"))
                    }

                    // Start failed before ever running (e.g. port conflict / port in use): tear the
                    // foreground service down AND clear the persisted enable flag, otherwise the
                    // service stays foreground forever and app-open autostart retries every launch.
                    A2aServiceLifecycleAction.STOP_AND_DISABLE -> {
                        settingsStore.update { it.copy(a2aEnabled = false) }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    A2aServiceLifecycleAction.STOP -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    A2aServiceLifecycleAction.NONE -> Unit
                }
            }
        }
    }

    private fun updateNotification(notification: android.app.Notification) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildStartingNotification() = NotificationCompat.Builder(this, A2A_SERVER_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle("A2A server")
        .setContentText("Starting A2A server")
        .setContentIntent(buildLaunchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun buildRunningNotification(url: String): android.app.Notification {
        val stopIntent = Intent(this, A2aServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, A2A_SERVER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("A2A server running")
            .setContentText("$url/a2a")
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }
}

/** What the foreground service should do for an observed [A2aServerManager] state. */
internal enum class A2aServiceLifecycleAction { NONE, RUNNING, STOP, STOP_AND_DISABLE }

/**
 * Pure lifecycle decision for the A2A foreground service. Extracted so the terminal-state handling
 * is unit-testable: a start that fails BEFORE ever running (error, not loading, never was running)
 * must STOP_AND_DISABLE — tear the service down AND clear the persisted enable flag — otherwise the
 * service is stuck foreground and app-open autostart keeps retrying. A stop AFTER a successful run is
 * a plain STOP (the enable flag is the user's choice, left untouched here).
 */
internal fun a2aServiceLifecycleAction(
    wasRunning: Boolean,
    isRunning: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
): A2aServiceLifecycleAction = when {
    isRunning -> A2aServiceLifecycleAction.RUNNING
    isLoading -> A2aServiceLifecycleAction.NONE
    !wasRunning && hasError -> A2aServiceLifecycleAction.STOP_AND_DISABLE
    wasRunning -> A2aServiceLifecycleAction.STOP
    else -> A2aServiceLifecycleAction.NONE
}
