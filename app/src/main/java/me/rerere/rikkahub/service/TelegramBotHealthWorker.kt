package me.rerere.rikkahub.service

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "TelegramBotHealth"
private const val HEALTH_WORK_NAME = "telegram_bot_health"
private const val HEALTH_INTERVAL_MIN = 30L

/**
 * Periodic health probe for [TelegramBotService]. If the bot is configured + enabled
 * but the service isn't currently running (OEM aggressive task-killing on Xiaomi /
 * Samsung / Honor / etc., or a crash that wasn't auto-revived), re-start it.
 *
 * Runs every [HEALTH_INTERVAL_MIN] minutes via WorkManager. Self-cancels if the user
 * disables the bot from Settings (the worker reads `current()` fresh each invocation).
 *
 * The earlier `dataSync` foreground-service-type bug ([commit on this branch] — see
 * AndroidManifest swap to specialUse) was the dominant cause of bot kills — this
 * worker is defense-in-depth for everything ELSE that can kill a foreground service:
 * background-process-limit on aggressive ROMs, OOM kills, surprise app standby, etc.
 */
class TelegramBotHealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val telegramPrefs: TelegramBotPreferences by inject()

    override suspend fun doWork(): Result {
        val cfg = runCatching { telegramPrefs.current() }.getOrNull()
        if (cfg == null || !cfg.isUsable) {
            Log.i(TAG, "doWork: bot disabled or unconfigured, no-op")
            return Result.success()
        }
        if (isServiceRunning()) {
            return Result.success()
        }
        Log.w(TAG, "doWork: bot is enabled but service isn't running — restarting")
        runCatching {
            TelegramBotService.start(applicationContext)
        }.onFailure {
            Log.e(TAG, "doWork: failed to restart service", it)
        }
        return Result.success()
    }

    /**
     * Whether [TelegramBotService] is currently a live process-component for this app.
     * `getRunningServices` is restricted to the calling app's services on Android 8+,
     * which is exactly what we want — we're checking ourselves.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TelegramBotService::class.java.name
        }
    }

    companion object {
        /**
         * Schedule (or no-op-replace) the periodic health probe. Idempotent — calls from
         * `RikkaHubApp.onCreate` and `CronBootReceiver` both end up with a single registered
         * worker thanks to `ExistingPeriodicWorkPolicy.KEEP`.
         */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TelegramBotHealthWorker>(
                HEALTH_INTERVAL_MIN, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HEALTH_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        /** Cancel the probe, e.g. when the user disables the bot from Settings. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(HEALTH_WORK_NAME)
        }
    }
}
