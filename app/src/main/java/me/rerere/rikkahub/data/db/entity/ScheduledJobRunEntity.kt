package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per scheduled-job fire. Persisted by CronJobWorker on every attempt
 * (success, failure, timeout, catchup-skip, concurrent-skip) so get_job_history
 * can give the LLM and user honest visibility into what actually happened.
 *
 * Capped at last 100 rows per jobId via ScheduledJobRunDao.trim() — that runs at
 * the end of every fire.
 */
@Entity(tableName = "scheduled_job_runs")
data class ScheduledJobRunEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val mode: String,                  // "llm" | "direct" — snapshot at fire time
    val scheduledAtMs: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,    // null while in flight, populated on completion
    val outcome: String,               // success|failed|skipped_catchup|timed_out|process_killed_replay|concurrent_skip
    val conversationId: String? = null,// null in mode='direct'
    val errorMessage: String? = null,  // ≤500 chars — truncate at write site
)
