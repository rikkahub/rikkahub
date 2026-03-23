package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sandbox")
data class SandboxEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
