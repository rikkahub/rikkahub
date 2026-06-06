package me.rerere.rikkahub.data.sync.archive

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import java.io.File

/**
 * Production [BackupArchiveEnvironment]. This is the only file in the archive
 * package allowed to depend on Android Context / SettingsStore / the settings
 * migrator; the build/restore core stays Android-free and unit-testable.
 */
class AndroidBackupArchiveEnvironment(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
) : BackupArchiveEnvironment {
    override val cacheDir: File get() = context.cacheDir
    override val filesDir: File get() = context.filesDir

    override fun databaseFile(name: String): File = context.getDatabasePath(name)

    override suspend fun readSettingsJson(): String =
        json.encodeToString(settingsStore.settingsFlow.value)

    override suspend fun restoreSettingsJson(json: String) {
        try {
            val migratedJson = SettingsJsonMigrator.migrate(json)
            val settings = this.json.decodeFromString<Settings>(migratedJson)
            settingsStore.update(settings)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("Failed to restore settings", e)
        }
    }
}
