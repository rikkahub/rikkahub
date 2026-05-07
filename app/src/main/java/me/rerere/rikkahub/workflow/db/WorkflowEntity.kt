package me.rerere.rikkahub.workflow.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 12 — workflow row. The full definition is stored as canonical JSON in
 * [definitionJson]; the projected columns ([name], [enabled], etc.) exist only so the
 * Settings UI can sort / filter without parsing every row. The JSON is the source of
 * truth — projected columns must be re-derived on every write.
 *
 * Triggered triggers (broadcast receivers, geofence client, etc.) read [enabled] off this
 * row — the auth/disable cycle does NOT round-trip through the JSON because that would
 * spam pointless rewrites.
 */
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** Canonical JSON. Source of truth. ≤ ~16KB in practice. */
    val definitionJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastRunAtMs: Long? = null,
    val lastRunStatus: String? = null,        // SUCCESS / FAILED / SKIPPED_*
    val lastRunError: String? = null,         // ≤500 chars truncated at write site
    @ColumnInfo(defaultValue = "0")
    val runsTodayCount: Int = 0,
    /** ISO local-date "yyyy-MM-dd" for daily-cap rollover. Empty string = never run. */
    @ColumnInfo(defaultValue = "''")
    val runsTodayDate: String = "",
)

/**
 * One row per workflow fire. Capped at 100 rows per workflow via
 * [WorkflowRunDao.trim] called from the engine post-fire.
 */
@Entity(
    tableName = "workflow_runs",
    indices = [Index(value = ["workflowId", "firedAtMs"])],
)
data class WorkflowRunEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val workflowId: String,
    val firedAtMs: Long,
    val status: String,                       // SUCCESS / FAILED / SKIPPED_*
    val durationMs: Long,
    val errorMessage: String? = null,
)
