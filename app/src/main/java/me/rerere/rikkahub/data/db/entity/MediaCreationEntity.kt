package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体创作的一个会话，[draft] 是输入区的草稿（JSON 序列化的 MediaCreationDraft）。
 */
@Entity(tableName = "media_creation_session")
data class MediaCreationSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("draft")
    val draft: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
)

/**
 * 会话时间线上的一项。再来一次、修改后重新生成都不会另起一项，而是在这一项下面多出一条记录（版本），
 * [selectedRecordId] 是当前显示的那一条。一项下面至少有一条记录。
 */
@Entity(
    tableName = "media_creation_node",
    foreignKeys = [
        ForeignKey(
            entity = MediaCreationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
data class MediaCreationNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("session_id")
    val sessionId: String,
    @ColumnInfo("selected_record_id")
    val selectedRecordId: String,
    @ColumnInfo("create_at")
    val createAt: Long,
)

/**
 * 一次生成，是 [nodeId] 那一项的一个版本。[params] / [inputs] / [outputs] 是 JSON，文件路径都相对于 filesDir。
 */
@Entity(
    tableName = "media_creation_record",
    foreignKeys = [
        ForeignKey(
            entity = MediaCreationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaCreationNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id"), Index("node_id")]
)
data class MediaCreationRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("session_id")
    val sessionId: String,
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("provider_id")
    val providerId: String,
    @ColumnInfo("provider_name")
    val providerName: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("params")
    val params: String,
    @ColumnInfo("inputs")
    val inputs: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("task_id")
    val taskId: String?,
    @ColumnInfo("error")
    val error: String?,
    @ColumnInfo("outputs")
    val outputs: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
)

data class MediaCreationSessionWithStats(
    @Embedded
    val session: MediaCreationSessionEntity,
    @ColumnInfo("node_count")
    val nodeCount: Int,
    @ColumnInfo("active_count")
    val activeCount: Int,
    @ColumnInfo("cover_outputs")
    val coverOutputs: String?,
)

/**
 * 时间线上一项当前显示的记录，以及它在这一项的所有版本里排第几（从 0 开始）。
 */
data class MediaCreationRecordWithVersion(
    @Embedded
    val record: MediaCreationRecordEntity,
    @ColumnInfo("version_index")
    val versionIndex: Int,
    @ColumnInfo("version_count")
    val versionCount: Int,
)
