package me.rerere.rikkahub.data.sync.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.TimeUnit

private const val TAG = "BackupAutomationManager"
private const val AUTO_BACKUP_WORK_NAME = "auto_backup_periodic"
private const val BACKUP_REMINDER_NOTIFICATION_ID = 404551
private const val BACKUP_REMINDER_NOTIFICATION_CHANNEL_ID = "backup_reminder"
private const val EXTRA_OPEN_SCREEN = "open_screen"
private const val OPEN_SCREEN_BACKUP = "backup"

class BackupAutomationManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
) {
    suspend fun runMigrationIfNeeded() {
        val settings = settingsStore.settingsFlow.value
        if (settings.backupAutomationMigrated) return
        settingsStore.update {
            it.copy(
                backupAutomationMigrated = true,
                webDavConfig = it.webDavConfig.copy(
                    autoBackupEnabled = isWebDavConfigured(it.webDavConfig),
                    autoBackupOnAppLaunch = isWebDavConfigured(it.webDavConfig),
                    autoBackupIntervalDays = it.webDavConfig.autoBackupIntervalDays.coerceAtLeast(1)
                ),
                s3Config = it.s3Config.copy(
                    autoBackupEnabled = isS3Configured(it.s3Config),
                    autoBackupOnAppLaunch = isS3Configured(it.s3Config),
                    autoBackupIntervalDays = it.s3Config.autoBackupIntervalDays.coerceAtLeast(1)
                )
            )
        }
    }

    fun ensurePeriodicWorkState() {
        val settings = settingsStore.settingsFlow.value
        val shouldEnable = hasAnyConfiguredAndEnabledProvider(settings)
        val workManager = WorkManager.getInstance(context)
        if (shouldEnable) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                AUTO_BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            workManager.cancelUniqueWork(AUTO_BACKUP_WORK_NAME)
        }
    }

    suspend fun runPeriodicIfNeeded() {
        val now = System.currentTimeMillis()
        runWebDavBackupIfNeeded(
            settingsStore.settingsFlow.value,
            now = now,
            isOnLaunch = false
        )
        runS3BackupIfNeeded(
            settingsStore.settingsFlow.value,
            now = now,
            isOnLaunch = false
        )
    }

    suspend fun runOnAppLaunchIfNeeded() {
        val now = System.currentTimeMillis()
        runWebDavBackupIfNeeded(
            settingsStore.settingsFlow.value,
            now = now,
            isOnLaunch = true
        )
        runS3BackupIfNeeded(
            settingsStore.settingsFlow.value,
            now = now,
            isOnLaunch = true
        )
    }

    suspend fun checkAndNotifyReminderIfNeeded() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.backupReminderEnabled) return
        if (!shouldRunByInterval(
                lastAtEpochMillis = settings.lastBackupReminderAtEpochMillis,
                intervalDays = settings.backupReminderIntervalDays,
                nowEpochMillis = System.currentTimeMillis()
            )
        ) {
            return
        }

        val webDavNeedReminder = isWebDavConfigured(settings.webDavConfig)
            && settings.webDavConfig.autoBackupEnabled
            && shouldRunBackupNow(settings.webDavConfig.lastBackupAtEpochMillis, settings.webDavConfig.autoBackupIntervalDays)
        val s3NeedReminder = isS3Configured(settings.s3Config)
            && settings.s3Config.autoBackupEnabled
            && shouldRunBackupNow(settings.s3Config.lastBackupAtEpochMillis, settings.s3Config.autoBackupIntervalDays)
        val needReminder = webDavNeedReminder || s3NeedReminder
        if (!needReminder) return

        val openBackupPageIntent = Intent(context, RouteActivity::class.java)
            .putExtra(EXTRA_OPEN_SCREEN, OPEN_SCREEN_BACKUP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        context.sendNotification(
            channelId = BACKUP_REMINDER_NOTIFICATION_CHANNEL_ID,
            notificationId = BACKUP_REMINDER_NOTIFICATION_ID
        ) {
            title = context.getString(R.string.backup_auto_reminder_title)
            content = context.getString(R.string.backup_auto_reminder_content)
            autoCancel = true
            useBigTextStyle = true
            useDefaults = true
            contentIntent = PendingIntent.getActivity(
                context,
                551,
                openBackupPageIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        settingsStore.update {
            it.copy(lastBackupReminderAtEpochMillis = System.currentTimeMillis())
        }
    }

    private suspend fun runWebDavBackupIfNeeded(settings: Settings, now: Long, isOnLaunch: Boolean) {
        val config = settings.webDavConfig
        if (!isWebDavConfigured(config)) return
        if (!config.autoBackupEnabled) return
        if (isOnLaunch && !config.autoBackupOnAppLaunch) return
        if (!shouldRunByInterval(config.lastBackupAtEpochMillis, config.autoBackupIntervalDays, now)) return
        val updated = runCatching {
            if (config.items.isEmpty()) {
                logSkip("webdav", "empty items")
                return
            }
            webDavSync.backup(config)
        }.fold(
            onSuccess = {
                config.copy(
                    lastBackupAtEpochMillis = now,
                    lastAutoBackupError = "",
                    lastAutoBackupFailedAtEpochMillis = 0L
                )
            },
            onFailure = { err ->
                Log.e(TAG, "runWebDavBackupIfNeeded failed", err)
                config.copy(
                    lastAutoBackupError = err.message ?: err::class.simpleName.orEmpty(),
                    lastAutoBackupFailedAtEpochMillis = now
                )
            }
        )
        settingsStore.update {
            it.copy(webDavConfig = updated)
        }
    }

    private suspend fun runS3BackupIfNeeded(settings: Settings, now: Long, isOnLaunch: Boolean) {
        val config = settings.s3Config
        if (!isS3Configured(config)) return
        if (!config.autoBackupEnabled) return
        if (isOnLaunch && !config.autoBackupOnAppLaunch) return
        if (!shouldRunByInterval(config.lastBackupAtEpochMillis, config.autoBackupIntervalDays, now)) return
        val updated = runCatching {
            if (config.items.isEmpty()) {
                logSkip("s3", "empty items")
                return
            }
            s3Sync.backupToS3(config)
        }.fold(
            onSuccess = {
                config.copy(
                    lastBackupAtEpochMillis = now,
                    lastAutoBackupError = "",
                    lastAutoBackupFailedAtEpochMillis = 0L
                )
            },
            onFailure = { err ->
                Log.e(TAG, "runS3BackupIfNeeded failed", err)
                config.copy(
                    lastAutoBackupError = err.message ?: err::class.simpleName.orEmpty(),
                    lastAutoBackupFailedAtEpochMillis = now
                )
            }
        )
        settingsStore.update {
            it.copy(s3Config = updated)
        }
    }

    private fun logSkip(provider: String, reason: String) {
        Log.i(TAG, "skip auto backup for $provider: $reason")
    }

    companion object {
        fun isWebDavConfigured(config: WebDavConfig): Boolean {
            return config.url.isNotBlank()
                && config.username.isNotBlank()
                && config.password.isNotBlank()
                && config.path.isNotBlank()
        }

        fun isS3Configured(config: S3Config): Boolean {
            return config.endpoint.isNotBlank()
                && config.accessKeyId.isNotBlank()
                && config.secretAccessKey.isNotBlank()
                && config.bucket.isNotBlank()
        }

        fun shouldRunBackupNow(lastBackupAtEpochMillis: Long, intervalDays: Int, nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
            return shouldRunByInterval(lastBackupAtEpochMillis, intervalDays, nowEpochMillis)
        }

        fun shouldRunByInterval(lastAtEpochMillis: Long, intervalDays: Int, nowEpochMillis: Long): Boolean {
            if (lastAtEpochMillis <= 0) return true
            val intervalMillis = TimeUnit.DAYS.toMillis(intervalDays.coerceAtLeast(1).toLong())
            return nowEpochMillis - lastAtEpochMillis >= intervalMillis
        }

        fun hasAnyConfiguredAndEnabledProvider(settings: Settings): Boolean {
            val webDavEnabled = isWebDavConfigured(settings.webDavConfig) && settings.webDavConfig.autoBackupEnabled
            val s3Enabled = isS3Configured(settings.s3Config) && settings.s3Config.autoBackupEnabled
            return webDavEnabled || s3Enabled
        }
    }
}
