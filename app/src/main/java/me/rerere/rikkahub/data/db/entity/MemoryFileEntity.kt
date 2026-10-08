package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "memory_file", primaryKeys = ["assistant_id", "path"])
data class MemoryFileEntity(
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
)
