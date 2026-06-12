package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One normalized dependency edge on a conversation's board: [blockerId] blocks [blockedId]
 * (SPEC.md M2). Edges are rows, NOT serialized arrays, so the cycle checker and
 * delete-unblocks-dependents work on relational data.
 *
 * The composite primary key IS the required unique `(conversationId, blockerId, blockedId)`
 * constraint — the same edge cannot persist twice, and there is no surrogate id to drift from
 * it. Cycle rejection (refusing `A blocks B` when a `B -> A` path exists) is the repository's
 * job, single enforcement point for tools and UI alike (maintainer decision #4).
 */
@Entity(
    tableName = "work_item_dependencies",
    primaryKeys = ["conversation_id", "blocker_id", "blocked_id"],
    indices = [
        // The claim path asks "unresolved blockers of X" — lookup by the blocked side.
        Index(value = ["conversation_id", "blocked_id"]),
    ],
)
data class WorkItemDependencyEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("blocker_id")
    val blockerId: String,
    @ColumnInfo("blocked_id")
    val blockedId: String,
)
