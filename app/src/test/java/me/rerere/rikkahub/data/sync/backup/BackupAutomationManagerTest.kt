package me.rerere.rikkahub.data.sync.backup

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.CloudSyncPrompt
import me.rerere.rikkahub.data.datastore.CloudSyncProvider
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackupAutomationManagerTest {
    @Test
    fun shouldRunByInterval_whenDue() {
        val now = System.currentTimeMillis()
        val last = now - TimeUnit.DAYS.toMillis(8)
        assertTrue(BackupAutomationManager.shouldRunByInterval(last, 7, now))
    }

    @Test
    fun shouldRunByInterval_whenNotDue() {
        val now = System.currentTimeMillis()
        val last = now - TimeUnit.DAYS.toMillis(3)
        assertFalse(BackupAutomationManager.shouldRunByInterval(last, 7, now))
    }

    @Test
    fun migrateSettings_onlyEnableConfiguredProviders() {
        val settings = Settings(
            webDavConfig = WebDavConfig(
                url = "https://dav.example.com",
                username = "u",
                password = "p",
                path = "backup"
            ),
            s3Config = S3Config(
                endpoint = "",
                accessKeyId = "",
                secretAccessKey = "",
                bucket = ""
            )
        )
        val migrated = BackupAutomationManager.migrateSettings(settings)

        assertTrue(migrated.backupAutomationMigrated)
        assertTrue(migrated.webDavConfig.autoBackupEnabled)
        assertTrue(migrated.webDavConfig.autoBackupOnAppLaunch)
        assertFalse(migrated.s3Config.autoBackupEnabled)
        assertFalse(migrated.s3Config.autoBackupOnAppLaunch)
    }

    @Test
    fun shouldExecuteProvider_skipWhenNoItems() {
        val now = System.currentTimeMillis()
        val shouldRun = BackupAutomationManager.shouldExecuteProvider(
            isConfigured = true,
            autoEnabled = true,
            onLaunchEnabled = true,
            isOnLaunch = false,
            hasItems = false,
            lastBackupAtEpochMillis = 0L,
            intervalDays = 7,
            nowEpochMillis = now
        )
        assertFalse(shouldRun)
    }

    @Test
    fun shouldSendReminder_respectsCooldown() {
        val now = System.currentTimeMillis()
        val settings = Settings(
            backupReminderEnabled = true,
            backupReminderIntervalDays = 7,
            lastBackupReminderAtEpochMillis = now - TimeUnit.DAYS.toMillis(1),
            webDavConfig = WebDavConfig(
                url = "https://dav.example.com",
                username = "u",
                password = "p",
                path = "backup",
                autoBackupEnabled = true,
                items = listOf(WebDavConfig.BackupItem.DATABASE),
                lastBackupAtEpochMillis = 0L
            )
        )
        assertFalse(BackupAutomationManager.shouldSendReminder(settings, now))
    }

    @Test
    fun hasAnyConfiguredAndEnabledProvider_onlyCountsConfigured() {
        val settings = Settings(
            webDavConfig = WebDavConfig(autoBackupEnabled = true),
            s3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "ak",
                secretAccessKey = "sk",
                bucket = "bucket",
                autoBackupEnabled = true
            )
        )
        assertTrue(BackupAutomationManager.hasAnyConfiguredAndEnabledProvider(settings))
    }

    @Test
    fun selectLatestPrompt_chooseNewerOne() {
        val prompts = listOf(
            CloudSyncPrompt(
                provider = CloudSyncProvider.WEBDAV,
                backupId = "a.zip",
                backupDisplayName = "a.zip",
                backupLastModifiedEpochMillis = 1000L
            ),
            CloudSyncPrompt(
                provider = CloudSyncProvider.S3,
                backupId = "b.zip",
                backupDisplayName = "b.zip",
                backupLastModifiedEpochMillis = 2000L
            )
        )
        val selected = BackupAutomationManager.selectLatestPrompt(prompts)
        assertEquals(CloudSyncProvider.S3, selected?.provider)
        assertEquals("b.zip", selected?.backupId)
    }

    @Test
    fun selectLatestPrompt_sameTimestampPreferWebDav() {
        val prompts = listOf(
            CloudSyncPrompt(
                provider = CloudSyncProvider.S3,
                backupId = "s3.zip",
                backupDisplayName = "s3.zip",
                backupLastModifiedEpochMillis = 2000L
            ),
            CloudSyncPrompt(
                provider = CloudSyncProvider.WEBDAV,
                backupId = "dav.zip",
                backupDisplayName = "dav.zip",
                backupLastModifiedEpochMillis = 2000L
            )
        )
        val selected = BackupAutomationManager.selectLatestPrompt(prompts)
        assertEquals(CloudSyncProvider.WEBDAV, selected?.provider)
        assertEquals("dav.zip", selected?.backupId)
    }
}
