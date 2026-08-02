package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_runs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_run_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index("assistant_id"),
        Index("parent_run_id"),
        Index("status"),
        Index(value = ["conversation_id", "status"]),
        Index(value = ["conversation_id", "created_at", "id"]),
    ],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("parent_run_id") val parentRunId: String? = null,
    val status: String,
    @ColumnInfo("config_snapshot_json") val configSnapshotJson: String,
    @ColumnInfo("error_json") val errorJson: String? = null,
    @ColumnInfo("summary_json") val summaryJson: String? = null,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("started_at") val startedAt: Long? = null,
    @ColumnInfo("finished_at") val finishedAt: Long? = null,
)

@Entity(
    tableName = "agent_steps",
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
    ],
)
data class AgentStepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("run_id") val runId: String,
    val sequence: Int,
    val kind: String,
    val status: String,
    @ColumnInfo("summary_json") val summaryJson: String? = null,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("finished_at") val finishedAt: Long? = null,
)

@Entity(
    tableName = "tool_executions",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentStepEntity::class,
            parentColumns = ["id"],
            childColumns = ["step_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "sequence"], unique = true),
        Index(value = ["run_id", "step_id", "tool_name", "tool_call_id", "input_sha256"], unique = true),
        Index(value = ["run_id", "step_id", "tool_call_id"], unique = true),
        Index("step_id"),
        Index("status"),
        Index(value = ["run_id", "tool_name", "input_sha256", "status"]),
    ],
)
data class ToolExecutionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("run_id") val runId: String,
    @ColumnInfo("step_id") val stepId: String,
    val sequence: Int,
    @ColumnInfo("tool_name") val toolName: String,
    val status: String,
    @ColumnInfo("tool_call_id") val toolCallId: String = "",
    @ColumnInfo("input_sha256") val inputSha256: String = "",
    @ColumnInfo("summary_json") val summaryJson: String? = null,
    @ColumnInfo("error_json") val errorJson: String? = null,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("started_at") val startedAt: Long? = null,
    @ColumnInfo("finished_at") val finishedAt: Long? = null,
)

@Entity(
    tableName = "agent_approvals",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ToolExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["tool_execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "sequence"], unique = true),
        Index("tool_execution_id"),
        Index("status"),
        Index(value = ["run_id", "status"]),
    ],
)
data class AgentApprovalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("run_id") val runId: String,
    @ColumnInfo("tool_execution_id") val toolExecutionId: String,
    val sequence: Int,
    val status: String,
    @ColumnInfo("summary_json") val summaryJson: String? = null,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("resolved_at") val resolvedAt: Long? = null,
)
