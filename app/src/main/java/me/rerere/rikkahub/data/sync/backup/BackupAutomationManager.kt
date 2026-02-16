package me.rerere.rikkahub.data.sync.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.datastore.CloudSyncPrompt
import me.rerere.rikkahub.data.datastore.CloudSyncProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import java.util.concurrent.TimeUnit

private const val TAG = "BackupAutomationManager"
private const val AUTO_BACKUP_WORK_NAME = "auto_backup_periodic"
private const val CLOUD_SYNC_CHECK_COOLDOWN_MILLIS = 3 * 60 * 1000L

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
        detectCloudNewerBackupIfNeeded(force = true)
    }

    suspend fun detectCloudNewerBackupIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        if (!shouldCheckCloudSyncNow(force, settings.lastCloudSyncCheckAtEpochMillis, now)) return
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
                    val item = when (val match = findWebDavBackupForPrompt(
                        prompt = prompt,
                        backups = webDavSync.listBackupFiles(settings.webDavConfig)
                    )) {
                        is BackupCandidateResult.Unique -> match.item
                        is BackupCandidateResult.NotFound -> error(match.message)
                        is BackupCandidateResult.Ambiguous -> error(match.message)
                    }
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
                    val item = when (val match = findS3BackupForPrompt(
                        prompt = prompt,
                        backups = s3Sync.listBackupFiles(settings.s3Config)
                    )) {
                        is BackupCandidateResult.Unique -> match.item
                        is BackupCandidateResult.NotFound -> error(match.message)
                        is BackupCandidateResult.Ambiguous -> error(match.message)
                    }
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

    private fun findWebDavBackupForPrompt(
        prompt: CloudSyncPrompt,
        backups: List<WebDavBackupItem>,
    ): BackupCandidateResult<WebDavBackupItem> {
        return findBackupForPrompt(
            prompt = prompt,
            backups = backups,
            idSelector = { it.displayName },
            timeSelector = { it.lastModified.toEpochMilli() },
            providerName = "WebDAV"
        )
    }

    private fun findS3BackupForPrompt(
        prompt: CloudSyncPrompt,
        backups: List<S3BackupItem>,
    ): BackupCandidateResult<S3BackupItem> {
        return findBackupForPrompt(
            prompt = prompt,
            backups = backups,
            idSelector = { it.key },
            timeSelector = { it.lastModified.toEpochMilli() },
            providerName = "S3"
        )
    }

    private fun <T> findBackupForPrompt(
        prompt: CloudSyncPrompt,
        backups: List<T>,
        idSelector: (T) -> String,
        timeSelector: (T) -> Long,
        providerName: String,
    ): BackupCandidateResult<T> {
        // 先按备份 ID 筛选，再用时间戳精确匹配，避免同名文件误恢复。
        val sameId = backups.filter { idSelector(it) == prompt.backupId }
        if (sameId.isEmpty()) {
            return BackupCandidateResult.NotFound(
                "$providerName backup not found by id: ${prompt.backupId}"
            )
        }

        val sameTimestamp = sameId.filter { timeSelector(it) == prompt.backupLastModifiedEpochMillis }
        return when (resolveBackupMatchStatus(
            promptId = prompt.backupId,
            promptLastModifiedEpochMillis = prompt.backupLastModifiedEpochMillis,
            candidateIdsAndModifiedMillis = backups.map { idSelector(it) to timeSelector(it) }
        )) {
            BackupMatchStatus.NOT_FOUND -> BackupCandidateResult.NotFound(
                "$providerName backup timestamp mismatch for id ${prompt.backupId}: ${prompt.backupLastModifiedEpochMillis}"
            )

            BackupMatchStatus.UNIQUE -> BackupCandidateResult.Unique(sameTimestamp.first())
            BackupMatchStatus.AMBIGUOUS -> BackupCandidateResult.Ambiguous(
                "$providerName backup candidates are not unique for id ${prompt.backupId}. Please restore manually."
            )
        }
    }

    private sealed interface BackupCandidateResult<out T> {
        data class Unique<T>(val item: T) : BackupCandidateResult<T>
        data class NotFound(val message: String) : BackupCandidateResult<Nothing>
        data class Ambiguous(val message: String) : BackupCandidateResult<Nothing>
    }

    companion object {
        enum class BackupMatchStatus {
            NOT_FOUND,
            UNIQUE,
            AMBIGUOUS,
        }

        fun resolveBackupMatchStatus(
            promptId: String,
            promptLastModifiedEpochMillis: Long,
            candidateIdsAndModifiedMillis: List<Pair<String, Long>>,
        ): BackupMatchStatus {
            val sameId = candidateIdsAndModifiedMillis.filter { it.first == promptId }
            if (sameId.isEmpty()) return BackupMatchStatus.NOT_FOUND
            val sameTimestamp = sameId.filter { it.second == promptLastModifiedEpochMillis }
            return when (sameTimestamp.size) {
                0 -> BackupMatchStatus.NOT_FOUND
                1 -> BackupMatchStatus.UNIQUE
                else -> BackupMatchStatus.AMBIGUOUS
            }
        }

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
            // 未选择备份项时不保留周期任务，避免无效唤醒。
            val webDavEnabled = isWebDavConfigured(settings.webDavConfig)
                && settings.webDavConfig.autoBackupEnabled
                && settings.webDavConfig.items.isNotEmpty()
            val s3Enabled = isS3Configured(settings.s3Config)
                && settings.s3Config.autoBackupEnabled
                && settings.s3Config.items.isNotEmpty()
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

        fun selectLatestPrompt(prompts: List<CloudSyncPrompt>): CloudSyncPrompt? {
            if (prompts.isEmpty()) return null
            // 仅提示一个来源：取最新，时间并列时优先 WebDAV。
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

        fun shouldCheckCloudSyncNow(
            force: Boolean,
            lastCheckAtEpochMillis: Long,
            nowEpochMillis: Long,
        ): Boolean {
            if (force) return true
            if (lastCheckAtEpochMillis <= 0L) return true
            return nowEpochMillis - lastCheckAtEpochMillis >= CLOUD_SYNC_CHECK_COOLDOWN_MILLIS
        }
    }
}
