package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次模型请求的 token 用量流水，每次请求（多步对话的每一步、每次重试）各记一行。
 *
 * 和消息分开存：删掉会话或消息后流水还在，统计不受影响；标题、翻译这类不落到消息里的请求也记在这里。
 * 引用的模型、助手和会话都可能已经被删除，所以不加外键。
 */
@Entity(
    tableName = "usage_record",
    indices = [Index(value = ["created_at"])]
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    // UsageSource 的名称
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("provider_id")
    val providerId: String?,
    @ColumnInfo("model_id")
    val modelId: String?,
    // 模型的 API 名称快照，模型被删除后靠它分辨
    @ColumnInfo("model_name")
    val modelName: String?,
    @ColumnInfo("assistant_id")
    val assistantId: String?,
    @ColumnInfo("conversation_id")
    val conversationId: String?,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Int,
    @ColumnInfo("completion_tokens")
    val completionTokens: Int,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Int,
)
