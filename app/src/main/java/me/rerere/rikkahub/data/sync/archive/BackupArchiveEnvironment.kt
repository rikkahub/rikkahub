package me.rerere.rikkahub.data.sync.archive

import java.io.File

/**
 * Seam between the archive build/restore core and the Android runtime. The
 * production adapter is backed by a Context + SettingsStore; tests provide a
 * fake rooted at temp directories so the core can run with no Android
 * dependencies.
 */
interface BackupArchiveEnvironment {
    val cacheDir: File
    val filesDir: File

    /** File for the database basename (wal/shm are derived as siblings). */
    fun databaseFile(name: String): File

    suspend fun readSettingsJson(): String
    suspend fun restoreSettingsJson(json: String)
}
