package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    // Staleness signal: a 3-month-old preference must not read as present-tense truth. Default 0 lets
    // legacy rows backfill at the v22->v23 migration with no AutoMigrationSpec.
    @ColumnInfo("created_at")
    val createdAt: Long = 0,
    // Staleness + the recency-fallback ranking key (top-k by updated_at when no embedding is usable).
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
)
