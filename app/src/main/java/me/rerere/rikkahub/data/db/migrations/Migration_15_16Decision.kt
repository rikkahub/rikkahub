package me.rerere.rikkahub.data.db.migrations

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.migrateToolNodes

/**
 * A single message_node row as loaded from the database for the 15->16 migration.
 *
 * @param messages the decoded messages, or null if the stored JSON could not be decoded.
 *   A null here means the row MUST be preserved untouched: we have no safe way to rewrite it.
 */
data class MigrationNodeRow(
    val id: String,
    val messages: List<UIMessage>?,
    val selectIndex: Int,
)

/**
 * The outcome of deciding what to do with one conversation's nodes during the 15->16 migration.
 */
sealed interface MigrationDecision {
    /** Leave every original row exactly as stored; perform no delete/reinsert. */
    data object Skip : MigrationDecision

    /**
     * Delete the conversation's existing rows and re-insert these (in order).
     * Only produced when every node decoded successfully and the tool migration changed something.
     */
    data class Rewrite(val rows: List<MigrationNodeRow>) : MigrationDecision
}

/**
 * Pure per-conversation keep/drop decision for Migration_15_16.
 *
 * Invariant being protected (issue #9): a node whose `messages` JSON failed to decode (messages == null)
 * must never be destroyed. The original code dropped undecodable rows before computing `changed`, then
 * issued DELETE-all + reinsert-only-decoded whenever a *sibling* node was tool-migrated, permanently
 * deleting the undecodable node and its messages.
 *
 * Conservative rule: if ANY node failed to decode, return [MigrationDecision.Skip] so the whole
 * conversation is left untouched. The few sibling nodes that stay in legacy tool format are harmless and
 * self-heal once their blob becomes decodable. Only when every node decodes AND the tool migration
 * actually changes something do we [MigrationDecision.Rewrite].
 */
fun decideMigration15To16(rows: List<MigrationNodeRow>): MigrationDecision {
    if (rows.isEmpty()) return MigrationDecision.Skip

    // If any row failed to decode, we cannot safely rewrite this conversation: a DELETE-all would
    // destroy the undecodable row, and reinserting only the decoded rows loses data. Preserve all.
    if (rows.any { it.messages == null }) return MigrationDecision.Skip

    val migrated = rows.migrateToolNodes(
        getMessages = { it.messages!! },
        setMessages = { row, msgs -> row.copy(messages = msgs) }
    )

    val changed = migrated.size != rows.size ||
        migrated.zip(rows).any { (a, b) -> a.messages != b.messages }
    if (!changed) return MigrationDecision.Skip

    return MigrationDecision.Rewrite(migrated)
}
