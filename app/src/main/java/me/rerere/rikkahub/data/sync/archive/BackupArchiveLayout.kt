package me.rerere.rikkahub.data.sync.archive

import me.rerere.rikkahub.data.files.FileFolders
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Single source of truth for the backward-compatible backup archive format.
 *
 * Entry names and the backup file name pattern are kept byte-identical to what
 * S3Sync/WebDavSync historically wrote so that existing archives round-trip.
 * Folder prefixes reuse the existing [FileFolders] constants rather than
 * redefining them.
 */
internal object BackupArchiveLayout {
    const val SETTINGS = "settings.json"

    /** Basename passed to Context.getDatabasePath(). */
    const val DB_NAME = "rikka_hub"

    const val DB = "rikka_hub.db"
    const val DB_WAL = "rikka_hub-wal"
    const val DB_SHM = "rikka_hub-shm"

    val UPLOAD_PREFIX get() = "${FileFolders.UPLOAD}/"
    val SKILLS_PREFIX get() = "${FileFolders.SKILLS}/"
    val FONTS_PREFIX get() = "${FileFolders.FONTS}/"

    private val FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun backupFileName(now: LocalDateTime): String =
        "backup_" + now.format(FILE_NAME_FORMAT) + ".zip"
}
