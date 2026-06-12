package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

private const val TAG = "GenerationFGS"

/**
 * Foreground service kept alive for the lifetime of any active generation.
 *
 * Started on the 0 -> 1 generation edge ([GenerationActivityTracker] STARTED) and stopped on the
 * 1 -> 0 edge (STOPPED). Mirrors [WebServerService]'s startForegroundCompat pattern but declares
 * FOREGROUND_SERVICE_TYPE_DATA_SYNC (see manifest for the type justification).
 *
 * Lock invariant: the PowerManager partial WakeLock + WifiManager WifiLock are owned exclusively by
 * this service and tied to its lifecycle. They are acquired on ACTION_START and released in the
 * single idempotent [releaseLocks], which runs on BOTH ACTION_STOP (the normal 1 -> 0 path) AND
 * onDestroy (terminal backstop if the service is killed without an explicit stop). The WakeLock is
 * non-reference-counted and acquired with a safety timeout aligned to the worst-case bound, so a
 * missed release self-heals. ChatService holds no lock objects -> no leak surface there.
 */
class GenerationForegroundService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.GENERATION_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.GENERATION_STOP"
        const val ACTION_RENEW = "me.rerere.rikkahub.action.GENERATION_RENEW"
        const val NOTIFICATION_ID = 2002

        // Safety timeout for the partial WakeLock. This is NOT a cap on total generation wall-clock:
        // an agentic tool/MCP/search loop (GenerationHandler.generateText) legitimately runs well past
        // 15 min across many sub-120s SSE streams. The timeout exists only as a leak backstop — if the
        // 1->0 release is somehow skipped (process killed mid-job), the lock self-releases instead of
        // pinning the CPU forever. While a job is genuinely making progress, [renew] re-acquires the
        // lock (resetting this timeout) on streaming chunks, so a long-but-live job keeps the CPU awake
        // and never stalls at the 15-min mark. Release on the real 1->0 edge is still the primary path.
        private const val WAKE_LOCK_TIMEOUT_MS = 15L * 60L * 1000L

        fun start(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_START
            }
            // startForegroundService requires the service to call startForeground within ~5s; we do
            // it synchronously as the first action in onStartCommand, so the contract is satisfied.
            context.startForegroundService(intent)
        }

        // Re-arms the WakeLock timeout while a job is still making forward progress. Called (throttled)
        // from ChatService on each streaming chunk so a long agentic loop never times out mid-job.
        // startService here is safe: the service is already foregrounded by [start] before any chunk
        // arrives, so this is not a background-start of a cold service.
        fun renew(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_RENEW
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Build the notification eagerly and call startForeground FIRST to avoid
                // ForegroundServiceDidNotStartInTimeException.
                startForegroundCompat()
                acquireLocks()
            }

            ACTION_RENEW -> {
                // Re-arm the WakeLock timeout for a still-live job. acquire(timeout) on a held,
                // non-reference-counted lock resets the timeout. No-op if the lock was never acquired.
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }

            ACTION_STOP -> {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            null -> {
                // Process restart with a null intent: nothing to keep alive, shut down cleanly.
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Terminal-path backstop: release even if the stop path was skipped (e.g. service killed).
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildOngoingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildOngoingNotification())
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "rikkahub:generation"
            ).apply { setReferenceCounted(false) }
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MS) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                // WIFI_MODE_FULL_LOW_LATENCY requires API 29; below it (minSdk 26) the
                // deprecated HIGH_PERF constant is the only full-power wifi lock mode.
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(
                wifiLockMode,
                "rikkahub:generation"
            ).apply { setReferenceCounted(false) }
        }
        wifiLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseLocks() {
        // Guarded against double-release via isHeld; both locks are non-reference-counted.
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        Log.d(TAG, "releaseLocks")
    }

    private fun buildLaunchPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoingNotification() =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_live_update_title))
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
}
