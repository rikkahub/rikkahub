package me.rerere.rikkahub.data.sync.archive

/**
 * Transport-agnostic description of what a backup archive contains. S3 and
 * WebDAV map their own config enums onto this so the archive core never depends
 * on transport-specific types.
 */
data class BackupArchiveSelection(
    val includeDatabase: Boolean,
    val includeFiles: Boolean,
)
