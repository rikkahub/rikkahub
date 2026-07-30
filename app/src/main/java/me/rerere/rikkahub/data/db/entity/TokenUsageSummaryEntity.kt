package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "token_usage_summary")
data class TokenUsageSummaryEntity(
    @PrimaryKey
    val id: Int = 0,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Long = 0,
    @ColumnInfo("completion_tokens")
    val completionTokens: Long = 0,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Long = 0,
)
