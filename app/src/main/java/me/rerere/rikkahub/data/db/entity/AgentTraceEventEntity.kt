package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A redacted append-only event. Its payload is the fixed [AgentTraceAttributes] JSON schema. */
@Entity(
    tableName = "agent_trace_events",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "sequence"], unique = true),
        Index(value = ["created_at"]),
    ],
)
data class AgentTraceEvent(
    @PrimaryKey val id: String,
    @ColumnInfo("run_id") val runId: String,
    val sequence: Int,
    val type: String,
    val status: String,
    @ColumnInfo("timestamp_millis") val timestampMillis: Long,
    @ColumnInfo("duration_millis") val durationMillis: Long? = null,
    @ColumnInfo("error_category") val errorCategory: String,
    @ColumnInfo("attributes_json") val attributesJson: String,
    @ColumnInfo("created_at") val createdAt: Long,
)

/** Compatibility alias for persistence-layer call sites. */
typealias AgentTraceEventEntity = AgentTraceEvent
