package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["entity_type", "entity_id"]),
        Index(value = ["next_retry_at"]),
        Index(value = ["created_at"]),
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo("mutation_id")
    val mutationId: String,
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("operation")
    val operation: String,
    @ColumnInfo("payload_json")
    val payloadJson: String?,
    @ColumnInfo("base_revision")
    val baseRevision: Long = 0,
    @ColumnInfo("payload_schema_version", defaultValue = "1")
    val payloadSchemaVersion: Int = 1,
    @ColumnInfo("retry_count", defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo("next_retry_at")
    val nextRetryAt: Long = 0,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
