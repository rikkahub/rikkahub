package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "sync_entity_revision",
    primaryKeys = ["entity_type", "entity_id"],
)
data class SyncEntityRevisionEntity(
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("revision")
    val revision: Long = 0,
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
)
