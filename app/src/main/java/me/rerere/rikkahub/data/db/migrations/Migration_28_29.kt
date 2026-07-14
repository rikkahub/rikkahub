package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Managed files: Long auto PK -> UUID String PK + cloud upload metadata.
 * Prefer UUID parsed from filename when path is folder/{uuid}.ext.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `managed_files_new` (
                `id` TEXT NOT NULL,
                `folder` TEXT NOT NULL,
                `relative_path` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `size_bytes` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `sha256` TEXT NOT NULL DEFAULT '',
                `object_key` TEXT NOT NULL DEFAULT '',
                `upload_status` TEXT NOT NULL DEFAULT 'local_only',
                `remote_revision` INTEGER NOT NULL DEFAULT 0,
                `deleted_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        val cursor = db.query(
            "SELECT id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at FROM managed_files"
        )
        cursor.use {
            while (it.moveToNext()) {
                val relativePath = it.getString(2) ?: continue
                val fileName = relativePath.substringAfterLast('/')
                val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
                val uuid = runCatching { UUID.fromString(stem).toString() }
                    .getOrElse { UUID.randomUUID().toString() }
                val folder = it.getString(1) ?: "upload"
                val displayName = it.getString(3) ?: fileName
                val mimeType = it.getString(4) ?: "application/octet-stream"
                val sizeBytes = it.getLong(5)
                val createdAt = it.getLong(6)
                val updatedAt = it.getLong(7)
                val args: Array<Any?> = arrayOf(
                    uuid,
                    folder,
                    relativePath,
                    displayName,
                    mimeType,
                    sizeBytes,
                    createdAt,
                    updatedAt,
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO managed_files_new
                    (id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at,
                     sha256, object_key, upload_status, remote_revision, deleted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', '', 'local_only', 0, NULL)
                    """.trimIndent(),
                    args,
                )
            }
        }
        db.execSQL("DROP TABLE managed_files")
        db.execSQL("ALTER TABLE managed_files_new RENAME TO managed_files")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_files_relative_path` ON `managed_files` (`relative_path`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_managed_files_folder` ON `managed_files` (`folder`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_managed_files_upload_status` ON `managed_files` (`upload_status`)"
        )
    }
}
