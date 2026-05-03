package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persistent scheduled job the LLM (or user) created.
 *
 * Two scheduling modes:
 *  - "once": fires once at [atUnixMs] then stays disabled (lastRunAtMs set, enabled=false).
 *  - "interval": fires every [intervalSeconds] seconds; nextRunAtMs always points at the
 *    upcoming fire. After each run the worker computes the next nextRunAtMs and re-enqueues.
 *
 * Persistence outlives app restart and device reboot — see CronBootReceiver.
 */
@Entity(tableName = "scheduled_jobs")
data class ScheduledJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    /** Assistant UUID string this job runs under. */
    val assistantId: String,
    /** "once" or "interval" */
    val scheduleType: String,
    /** Unix ms — for scheduleType="once" the fire time; for "interval" unused. */
    val atUnixMs: Long? = null,
    /** Seconds — for scheduleType="interval" the period; for "once" unused. */
    val intervalSeconds: Int? = null,
    val enabled: Boolean = true,
    val createdAtMs: Long,
    val lastRunAtMs: Long? = null,
    val nextRunAtMs: Long? = null,
)
