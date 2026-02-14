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
import me.rerere.rikkahub.data.datastore.CloudSyncPrompt
import me.rerere.rikkahub.data.datastore.CloudSyncProvider
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
        settingsStore.update { migrateSettings(it) }
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

    suspend fun detectCloudNewerBackupOnLaunch() {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        val prompts = buildList {
            if (isWebDavConfigured(settings.webDavConfig)) {
                runCatching {
                    webDavSync.listBackupFiles(settings.webDavConfig)
                        .maxByOrNull { it.lastModified }
                        ?.takeIf { it.lastModified.toEpochMilli() > settings.webDavConfig.lastBackupAtEpochMillis }
                        ?.let { backup ->
                            add(
                                CloudSyncPrompt(
                                    provider = CloudSyncProvider.WEBDAV,
                                    backupId = backup.displayName,
                                    backupDisplayName = backup.displayName,
                                    backupLastModifiedEpochMillis = backup.lastModified.toEpochMilli(),
                                )
                            )
                        }
                }.onFailure {
                    Log.w(TAG, "detectCloudNewerBackupOnLaunch webdav failed", it)
                }
            }

            if (isS3Configured(settings.s3Config)) {
                runCatching {
                    s3Sync.listBackupFiles(settings.s3Config)
                        .maxByOrNull { it.lastModified }
                        ?.takeIf { it.lastModified.toEpochMilli() > settings.s3Config.lastBackupAtEpochMillis }
                        ?.let { backup ->
                            add(
                                CloudSyncPrompt(
                                    provider = CloudSyncProvider.S3,
                                    backupId = backup.key,
                                    backupDisplayName = backup.displayName,
                                    backupLastModifiedEpochMillis = backup.lastModified.toEpochMilli(),
                                )
                            )
                        }
                }.onFailure {
                    Log.w(TAG, "detectCloudNewerBackupOnLaunch s3 failed", it)
                }
            }
        }

        settingsStore.update {
            it.copy(
                pendingCloudSyncPrompt = selectLatestPrompt(prompts),
                lastCloudSyncCheckAtEpochMillis = now
            )
        }
    }

    suspend fun dismissPendingCloudSyncPrompt() {
        settingsStore.update {
            it.copy(pendingCloudSyncPrompt = null)
        }
    }

    suspend fun syncFromPendingCloudPrompt(): Result<Unit> {
        val settings = settingsStore.settingsFlow.value
        val prompt = settings.pendingCloudSyncPrompt
            ?: return Result.failure(IllegalStateException("No pending cloud sync prompt"))
        val now = System.currentTimeMillis()
        return runCatching {
            when (prompt.provider) {
                CloudSyncProvider.WEBDAV -> {
                    val item = webDavSync.listBackupFiles(settings.webDavConfig)
                        .firstOrNull { it.displayName == prompt.backupId }
                        ?: error("WebDAV backup not found: ${prompt.backupId}")
                    webDavSync.restore(settings.webDavConfig, item)
                    settingsStore.update {
                        it.copy(
                            pendingCloudSyncPrompt = null,
                            webDavConfig = it.webDavConfig.copy(
                                lastBackupAtEpochMillis = now,
                                lastAutoBackupError = "",
                                lastAutoBackupFailedAtEpochMillis = 0L
                            )
                        )
                    }
                }

                CloudSyncProvider.S3 -> {
                    val item = s3Sync.listBackupFiles(settings.s3Config)
                        .firstOrNull { it.key == prompt.backupId }
                        ?: error("S3 backup not found: ${prompt.backupId}")
                    s3Sync.restoreFromS3(settings.s3Config, item)
                    settingsStore.update {
                        it.copy(
                            pendingCloudSyncPrompt = null,
                            s3Config = it.s3Config.copy(
                                lastBackupAtEpochMillis = now,
                                lastAutoBackupError = "",
                                lastAutoBackupFailedAtEpochMillis = 0L
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun checkAndNotifyReminderIfNeeded() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.backupReminderEnabled) return
        if (!shouldSendReminder(settings, System.currentTimeMillis())) return

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
        if (!shouldExecuteProvider(
                isConfigured = isWebDavConfigured(config),
                autoEnabled = config.autoBackupEnabled,
                onLaunchEnabled = config.autoBackupOnAppLaunch,
                isOnLaunch = isOnLaunch,
                hasItems = config.items.isNotEmpty(),
                lastBackupAtEpochMillis = config.lastBackupAtEpochMillis,
                intervalDays = config.autoBackupIntervalDays,
                nowEpochMillis = now
            )
        ) return
        val updated = runCatching {
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
        if (!shouldExecuteProvider(
                isConfigured = isS3Configured(config),
                autoEnabled = config.autoBackupEnabled,
                onLaunchEnabled = config.autoBackupOnAppLaunch,
                isOnLaunch = isOnLaunch,
                hasItems = config.items.isNotEmpty(),
                lastBackupAtEpochMillis = config.lastBackupAtEpochMillis,
                intervalDays = config.autoBackupIntervalDays,
                nowEpochMillis = now
            )
        ) return
        val updated = runCatching {
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

    companion object {
        fun migrateSettings(settings: Settings): Settings {
            return settings.copy(
                backupAutomationMigrated = true,
                webDavConfig = settings.webDavConfig.copy(
                    autoBackupEnabled = isWebDavConfigured(settings.webDavConfig),
                    autoBackupOnAppLaunch = isWebDavConfigured(settings.webDavConfig),
                    autoBackupIntervalDays = settings.webDavConfig.autoBackupIntervalDays.coerceAtLeast(1)
                ),
                s3Config = settings.s3Config.copy(
                    autoBackupEnabled = isS3Configured(settings.s3Config),
                    autoBackupOnAppLaunch = isS3Configured(settings.s3Config),
                    autoBackupIntervalDays = settings.s3Config.autoBackupIntervalDays.coerceAtLeast(1)
                )
            )
        }

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

        fun shouldExecuteProvider(
            isConfigured: Boolean,
            autoEnabled: Boolean,
            onLaunchEnabled: Boolean,
            isOnLaunch: Boolean,
            hasItems: Boolean,
            lastBackupAtEpochMillis: Long,
            intervalDays: Int,
            nowEpochMillis: Long,
        ): Boolean {
            if (!isConfigured) return false
            if (!autoEnabled) return false
            if (isOnLaunch && !onLaunchEnabled) return false
            if (!hasItems) return false
            return shouldRunByInterval(lastBackupAtEpochMillis, intervalDays, nowEpochMillis)
        }

        fun shouldSendReminder(settings: Settings, nowEpochMillis: Long): Boolean {
            if (!settings.backupReminderEnabled) return false
            if (!shouldRunByInterval(settings.lastBackupReminderAtEpochMillis, settings.backupReminderIntervalDays, nowEpochMillis)) {
                return false
            }

            val webDavNeedReminder = shouldExecuteProvider(
                isConfigured = isWebDavConfigured(settings.webDavConfig),
                autoEnabled = settings.webDavConfig.autoBackupEnabled,
                onLaunchEnabled = settings.webDavConfig.autoBackupOnAppLaunch,
                isOnLaunch = false,
                hasItems = settings.webDavConfig.items.isNotEmpty(),
                lastBackupAtEpochMillis = settings.webDavConfig.lastBackupAtEpochMillis,
                intervalDays = settings.webDavConfig.autoBackupIntervalDays,
                nowEpochMillis = nowEpochMillis
            )
            val s3NeedReminder = shouldExecuteProvider(
                isConfigured = isS3Configured(settings.s3Config),
                autoEnabled = settings.s3Config.autoBackupEnabled,
                onLaunchEnabled = settings.s3Config.autoBackupOnAppLaunch,
                isOnLaunch = false,
                hasItems = settings.s3Config.items.isNotEmpty(),
                lastBackupAtEpochMillis = settings.s3Config.lastBackupAtEpochMillis,
                intervalDays = settings.s3Config.autoBackupIntervalDays,
                nowEpochMillis = nowEpochMillis
            )
            return webDavNeedReminder || s3NeedReminder
        }

        fun selectLatestPrompt(prompts: List<CloudSyncPrompt>): CloudSyncPrompt? {
            if (prompts.isEmpty()) return null
            return prompts.maxWithOrNull { a, b ->
                val timeDiff = a.backupLastModifiedEpochMillis.compareTo(b.backupLastModifiedEpochMillis)
                if (timeDiff != 0) {
                    timeDiff
                } else {
                    when {
                        a.provider == b.provider -> 0
                        a.provider == CloudSyncProvider.WEBDAV -> 1
                        else -> -1
                    }
                }
            }
        }
    }
}
