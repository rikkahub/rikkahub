package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.ai.runtime.board.WorkItem
import me.rerere.ai.runtime.board.WorkItemStatus
import kotlin.uuid.Uuid

/**
 * One work item on a per-conversation board (SPEC.md M2). The persisted shape of the neutral
 * `me.rerere.ai.runtime.board.WorkItem` DTO plus persistence-only concerns: timestamps,
 * [metadataJson], and the claim lease ([leaseExpiresAt] — the backstop that lets orphan recovery
 * release claims whose owning execution handle died without a trace).
 *
 * Ownership/claim invariants (SingleOwnerClaim, legal transitions) are NOT enforced here — the
 * repository layer is the single enforcement point for tool calls and the UI alike (maintainer
 * decision #4); this entity is a dumb row.
 */
@Entity(
    tableName = "work_items",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["owner_handle_id"]),
    ],
)
data class WorkItemEntity(
    @PrimaryKey
    val id: String,
    /** Board scope: one board per conversation. */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    val subject: String,
    @ColumnInfo(defaultValue = "")
    val description: String = "",
    /** Present-continuous label shown while the item is in progress. */
    @ColumnInfo("active_form")
    val activeForm: String? = null,
    /** Persisted name of a [WorkItemStatus]; renaming an entry is a data-format break. */
    val status: String,
    /** The execution handle currently holding the claim, null when unclaimed. */
    @ColumnInfo("owner_handle_id")
    val ownerHandleId: String? = null,
    @ColumnInfo("owner_name")
    val ownerName: String? = null,
    @ColumnInfo("metadata_json", defaultValue = "{}")
    val metadataJson: String = "{}",
    /** Claim lease expiry (epoch millis); null when unclaimed or the claim has no lease. */
    @ColumnInfo("lease_expires_at")
    val leaseExpiresAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    /**
     * Null when [status] or an id is unparseable — a corrupt row must surface to the caller,
     * not silently masquerade as a fresh Pending item (which would double-assign work).
     */
    fun toWorkItem(): WorkItem? = runCatching {
        WorkItem(
            id = Uuid.parse(id),
            conversationId = Uuid.parse(conversationId),
            subject = subject,
            description = description,
            activeForm = activeForm,
            status = WorkItemStatus.valueOf(status),
            ownerHandleId = ownerHandleId,
            ownerName = ownerName,
        )
    }.getOrNull()

    companion object {
        fun fromWorkItem(
            item: WorkItem,
            createdAt: Long,
            updatedAt: Long,
            metadataJson: String = "{}",
            leaseExpiresAt: Long? = null,
        ): WorkItemEntity = WorkItemEntity(
            id = item.id.toString(),
            conversationId = item.conversationId.toString(),
            subject = item.subject,
            description = item.description,
            activeForm = item.activeForm,
            status = item.status.name,
            ownerHandleId = item.ownerHandleId,
            ownerName = item.ownerName,
            metadataJson = metadataJson,
            leaseExpiresAt = leaseExpiresAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
