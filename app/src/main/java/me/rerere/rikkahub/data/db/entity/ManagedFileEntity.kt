package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "managed_files",
    indices = [
        Index(value = ["relative_path"], unique = true),
        Index(value = ["folder"]),
        Index(value = ["upload_status"]),
    ]
)
data class ManagedFileEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("folder")
    val folder: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("sha256", defaultValue = "''")
    val sha256: String = "",
    @ColumnInfo("object_key", defaultValue = "''")
    val objectKey: String = "",
    /**
     * local_only | pending_upload | uploading | ready | pending_download | failed | deleted
     */
    @ColumnInfo("upload_status", defaultValue = "'local_only'")
    val uploadStatus: String = UPLOAD_LOCAL_ONLY,
    @ColumnInfo("remote_revision", defaultValue = "0")
    val remoteRevision: Long = 0,
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
) {
    companion object {
        const val UPLOAD_LOCAL_ONLY = "local_only"
        const val UPLOAD_PENDING = "pending_upload"
        const val UPLOAD_UPLOADING = "uploading"
        const val UPLOAD_READY = "ready"
        const val UPLOAD_PENDING_DOWNLOAD = "pending_download"
        const val UPLOAD_FAILED = "failed"
        const val UPLOAD_DELETED = "deleted"
    }
}
