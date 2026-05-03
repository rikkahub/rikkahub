package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Re-schedules every enabled cron job after device reboot or app upgrade. Receivers can't run
 * suspending code directly — we use goAsync() and a launched coroutine.
 */
class CronBootReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: CronJobScheduler by inject()
    private val telegramPrefs: me.rerere.rikkahub.data.telegram.TelegramBotPreferences by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.scheduleAllEnabled()
                // Re-start Telegram bot if it was enabled
                val cfg = try { telegramPrefs.current() } catch (_: Throwable) { null }
                if (cfg != null && cfg.isUsable) {
                    me.rerere.rikkahub.service.TelegramBotService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
