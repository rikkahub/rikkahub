package me.rerere.rikkahub.service

import com.cronutils.model.time.ExecutionTime
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Pure function. Given a job's catchup policy and the last-run / now timestamps,
 * compute (a) how many WorkManager enqueues to issue and at what stagger, and
 * (b) how many run-history rows to write with outcome='skipped_catchup' for the
 * windows we deliberately drop.
 *
 * Lives outside CronBootReceiver so it can be unit-tested without WorkManager.
 */
object CatchupPlanner {

    private const val FIRE_ALL_CAP = 20
    private const val FIRE_ALL_STAGGER_MS = 2_000L

    data class CatchupPlan(
        /** Delays to pass to OneTimeWorkRequestBuilder.setInitialDelay. */
        val fireDelaysMs: List<Long>,
        /** Number of windows we deliberately did NOT fire (record as 'skipped_catchup'). */
        val skippedCatchupCount: Int,
    )

    fun plan(job: ScheduledJobEntity, lastRunMs: Long?, nowMs: Long): CatchupPlan {
        if (job.scheduleType != "cron") return CatchupPlan(emptyList(), 0)
        val expr = job.cronExpression ?: return CatchupPlan(emptyList(), 0)
        val cron = CronExpressionParser.parse(expr).getOrNull() ?: return CatchupPlan(emptyList(), 0)
        val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val et = ExecutionTime.forCron(cron)

        val from = (lastRunMs ?: job.createdAtMs)
        val missedCount = countMatchesBetween(et, zone, fromMsExclusive = from, toMsInclusive = nowMs)

        return when (job.catchup) {
            "skip"      -> CatchupPlan(emptyList(), missedCount)
            "fire_once" -> if (missedCount == 0) CatchupPlan(emptyList(), 0)
                           else CatchupPlan(listOf(0L), missedCount - 1)
            "fire_all"  -> {
                val capped = missedCount.coerceAtMost(FIRE_ALL_CAP)
                val delays = (0 until capped).map { it * FIRE_ALL_STAGGER_MS }
                val skipped = (missedCount - capped).coerceAtLeast(0)
                CatchupPlan(delays, skipped)
            }
            else -> CatchupPlan(emptyList(), 0)
        }
    }

    /** Walk forward from [fromMsExclusive] in cron-utils, counting matches up to [toMsInclusive]. */
    private fun countMatchesBetween(
        et: ExecutionTime,
        zone: ZoneId,
        fromMsExclusive: Long,
        toMsInclusive: Long,
    ): Int {
        if (toMsInclusive <= fromMsExclusive) return 0
        var cursor = Instant.ofEpochMilli(fromMsExclusive).atZone(zone)
        var count = 0
        while (true) {
            val next = et.nextExecution(cursor).orElse(null) ?: break
            val nextMs = next.toInstant().toEpochMilli()
            if (nextMs > toMsInclusive) break
            count++
            cursor = next
            // Safety net — should never happen but bail if we somehow loop > 10000.
            if (count > 10_000) break
        }
        return count
    }
}
