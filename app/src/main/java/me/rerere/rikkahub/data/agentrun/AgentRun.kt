package me.rerere.rikkahub.data.agentrun

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 24 — unified `AgentRun` ledger row.
 *
 * One persisted record per autonomous run across all five autonomous execution paths:
 * cron jobs, workflows, sub-agents, Telegram conversations, and external automation.
 * This is the *shadow* ledger — additive observability + boot recovery. The per-domain
 * detail tables (`scheduled_job_runs`, `workflow_runs`) stay the authoritative source of
 * truth for their domains; this table is the single cross-pillar surface.
 *
 * A row is opened on the first lifecycle transition (`queued` or `running`) and updated
 * in place as the run progresses. On app start, [AgentRunBootRecovery] flips any row that
 * was left `running` / `awaiting_approval` by a killed process to `process_lost`.
 *
 * Retention: capped at [me.rerere.rikkahub.data.agentrun.AgentRunDefaults.RETENTION_CAP]
 * rows total; FIFO eviction of the oldest terminal rows when the cap is hit.
 */
@Entity(
    tableName = "agent_runs",
    indices = [
        Index(name = "idx_runs_status", value = ["status"]),
        Index(name = "idx_runs_kind_dom", value = ["kind", "domain_id"]),
        Index(name = "idx_runs_parent", value = ["parent_run_id"]),
        Index(name = "idx_runs_updated_at", value = ["updated_at_ms"]),
    ],
)
data class AgentRun(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Discriminator for which autonomous path produced this row. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /**
     * Per-kind domain id. cron: `jobId:runAtMs`; workflow: workflow id; subagent: sub-agent
     * run id; telegram: conversation id; external_automation: request id (or a generated id).
     */
    @ColumnInfo(name = "domain_id")
    val domainId: String,

    /** Set for sub-agents dispatched from another run; nullable otherwise. */
    @ColumnInfo(name = "parent_run_id")
    val parentRunId: String? = null,

    /** Lifecycle status — see [AgentRunStatus]. Stored as the enum's `name`. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,

    /** Null while in `queued` / `awaiting_approval`. */
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long? = null,

    /** Null until the run reaches a terminal status. */
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long? = null,

    /** Short error code / message; full detail stays in the domain table. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    /** Per-kind freeform JSON: trigger source, model id, sub-agent label, etc. Capped at 4 KB. */
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String? = null,
)

/**
 * Lifecycle status for a ledger row. Names match the Phase 9.5 scheduler vocabulary so the
 * cross-pillar boot recovery can reuse the stranded-row sweep idea verbatim.
 */
@Suppress("EnumEntryName")
enum class AgentRunStatus {
    /** Accepted, not started yet. */
    queued,

    /** Tool-approval prompt is up; counts as not-running. */
    awaiting_approval,

    /** Actively executing. */
    running,

    /** Terminal — finished cleanly. */
    succeeded,

    /** Terminal — `last_error` populated. */
    failed,

    /** Terminal — user/system cancellation. */
    cancelled,

    /** Terminal — boot-recovery sweep flipped a stranded `running` row. */
    process_lost;

    val isTerminal: Boolean
        get() = this == succeeded || this == failed || this == cancelled || this == process_lost

    companion object {
        /** Statuses considered "in flight" — eligible for the boot-recovery sweep. */
        val IN_FLIGHT: Set<AgentRunStatus> = setOf(queued, awaiting_approval, running)

        /** Parse a stored status name, defaulting to [running] for an unknown value. */
        fun fromName(name: String?): AgentRunStatus =
            entries.firstOrNull { it.name == name } ?: running
    }
}

/**
 * Discriminator for the five autonomous paths. Stored as [AgentRunKind.wire] in the
 * `kind` column.
 */
enum class AgentRunKind(val wire: String) {
    Cron("cron"),
    Workflow("workflow"),
    SubAgent("subagent"),
    Telegram("telegram"),
    ExternalAutomation("external_automation");

    companion object {
        fun fromWire(wire: String?): AgentRunKind? = entries.firstOrNull { it.wire == wire }
    }
}

object AgentRunDefaults {
    /** Total rows kept across all kinds before FIFO eviction of the oldest terminal rows. */
    const val RETENTION_CAP = 1000

    /** `metadata_json` size bound — prevents unbounded growth from a runaway writer. */
    const val METADATA_MAX_BYTES = 4 * 1024

    /** A run still `in flight` and untouched for longer than this on app start is stranded. */
    const val STRANDED_THRESHOLD_MS = 30L * 60_000L
}
