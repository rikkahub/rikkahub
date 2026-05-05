package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persistent scheduled job. Two modes (llm / direct) and two timing types (once / cron).
 *
 * v20 → v21 migration:
 *  - Adds: mode, actionsJson, cronExpression, timezone, startAtUnixMs, endAtUnixMs,
 *    maxRuns, runsSoFar, catchup, description, tags
 *  - Translates rows with scheduleType='interval' to scheduleType='cron' with
 *    cronExpression='@every Ns'. intervalSeconds is kept for read-only history but
 *    never written to by post-v21 code.
 *  - prompt becomes nullable to accommodate mode='direct' rows.
 */
@Entity(tableName = "scheduled_jobs")
data class ScheduledJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Required iff mode='llm'. Nullable since v21 (mode='direct' has no prompt). */
    val prompt: String? = null,
    val assistantId: String,
    /** "once" | "cron". v20's "interval" rows migrate to "cron" + "@every Ns". */
    val scheduleType: String,
    val atUnixMs: Long? = null,
    /** v20→v21 migrated to cron_expression='@every Ns'. Read-only history; never written. */
    val intervalSeconds: Int? = null,
    val enabled: Boolean = true,
    val createdAtMs: Long,
    val lastRunAtMs: Long? = null,
    val nextRunAtMs: Long? = null,

    @ColumnInfo(defaultValue = "'llm'")
    val mode: String = "llm",                       // "llm" | "direct"
    val actionsJson: String? = null,                // JSON array; required iff mode='direct'
    val cronExpression: String? = null,             // required iff scheduleType='cron'
    val timezone: String? = null,                   // optional IANA id; null = device default
    val startAtUnixMs: Long? = null,                // optional, cron only
    val endAtUnixMs: Long? = null,                  // optional, cron only
    val maxRuns: Int? = null,                       // optional, cron only — successful fires
    @ColumnInfo(defaultValue = "0")
    val runsSoFar: Int = 0,
    @ColumnInfo(defaultValue = "'fire_once'")
    val catchup: String = "fire_once",              // "skip" | "fire_once" | "fire_all"
    val description: String? = null,                // optional, ≤500 chars
    val tags: String? = null,                       // optional comma-joined ids
)
