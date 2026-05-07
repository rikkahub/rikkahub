package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Phase 12 — time / cron family.
 *
 * Reuses WorkManager (same backend as scheduled jobs) but with workflow-scoped unique-work
 * names so one workflow's schedule doesn't collide with another's, and so unsync removes
 * exactly the disabled workflow's worker without touching anything else.
 *
 * v1 supports the time_of_day + days_of_week subset. The `cron` field is also accepted
 * (per spec — same dialect as scheduled jobs), parsed by computing the next ms-after-now
 * for the most common patterns: `@every Ns`, `@hourly`, `@daily`, plus straight HH:mm. For
 * arbitrary 5-field cron, we fall back to one-shot scheduling at a roughly-correct time and
 * rely on the next fire to reschedule. Full-syntax cron is explicitly out of scope (see spec
 * "Decisions you must make"). The validator already rejected anything we couldn't translate.
 *
 * We use [WorkflowTimeCronWorker] (separate file) which receives the workflow id and
 * dispatches into the engine.
 */
internal class TimeCronTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "time_cron"

    @Volatile private var lastSnapshot: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        fireCallback = callback
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }
        // Cancel removed
        for (id in previous.keys - current.keys) {
            cancelWork(id)
        }
        // Schedule added or changed
        for ((id, wf) in current) {
            val prev = previous[id]
            if (prev == null
                || prev.trigger != wf.trigger
                || prev.updatedAtMs != wf.updatedAtMs
                || !prev.enabled
            ) {
                scheduleWork(wf)
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() {
        for (wf in lastSnapshot) cancelWork(wf.id)
        lastSnapshot = emptyList()
        fireCallback = null
    }

    fun cancelWork(workflowId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(workflowId)) }
            .onFailure { Log.w(TAG, "time_cron: cancel work failed for $workflowId", it) }
    }

    private fun scheduleWork(wf: WorkflowDefinition) {
        val spec = wf.trigger as? TriggerSpec.TimeCron ?: return
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

        // Periodic path: time_of_day-based or @every — both reduce to a 24h period for
        // time_of_day, or the parsed N-second period for @every. WorkManager's smallest
        // period is 15 minutes, so very-short cycles fall back to one-shot rescheduling
        // from the worker (worker re-enqueues itself).
        val periodMs = derivePeriodMs(spec)
        if (periodMs != null && periodMs >= 15 * 60 * 1000L) {
            val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
            val delay = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = PeriodicWorkRequestBuilder<WorkflowTimeCronWorker>(periodMs, TimeUnit.MILLISECONDS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
                .setConstraints(Constraints.NONE)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName(wf.id), ExistingPeriodicWorkPolicy.REPLACE, req,
                )
            }.onFailure { Log.w(TAG, "time_cron: periodic enqueue failed for ${wf.id}", it) }
            return
        }

        // One-shot path: schedule the next fire; the worker re-enqueues itself on completion.
        val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
        val delay = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val req = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(wf.id), ExistingWorkPolicy.REPLACE, req,
            )
        }.onFailure { Log.w(TAG, "time_cron: one-shot enqueue failed for ${wf.id}", it) }
    }

    /** Internal — fires the workflow then re-enqueues if needed. Called from worker. */
    suspend fun onWorkerFired(workflowId: String) {
        val cb = fireCallback ?: return
        // Post-boot or post-process-death race: WorkManager wakes the worker before
        // [TriggerRegistry.start] has emitted from the repo's flow, leaving `lastSnapshot`
        // empty. Fall back to a direct repository fetch so the fire isn't silently dropped.
        // The engine still re-checks enabled / cooldown / conditions, so this is safe.
        val wf = lastSnapshot.firstOrNull { it.id == workflowId }
            ?: run {
                val loaded = me.rerere.rikkahub.workflow.trigger.TimeCronWorkerHelper
                    .repositoryLookup(workflowId)
                if (loaded == null) return
                if (!loaded.entity.enabled) return
                loaded.definition
            }
        scope.launch(Dispatchers.IO) {
            runCatching { cb.onFire(wf.id, wf.trigger) }
                .onFailure { Log.w(TAG, "time_cron: fire callback failed for $workflowId", it) }
        }
        // For one-shot path (period < 15min or null), re-enqueue with the next fire.
        val periodMs = (wf.trigger as? TriggerSpec.TimeCron)?.let { derivePeriodMs(it) }
        if (periodMs == null || periodMs < 15 * 60 * 1000L) {
            scheduleWork(wf)
        }
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
        const val KEY_WORKFLOW_ID = "workflow_id"
        fun workName(workflowId: String) = "wf_timecron_$workflowId"

        /**
         * Returns null for arbitrary cron (one-shot path) or the period in ms for the
         * supported subset. Daily HH:mm = 24h. @every Ns = N seconds. @hourly = 1h.
         * @daily = 24h. days_of_week with time_of_day still uses 24h period (worker's
         * fire skips when day doesn't match).
         */
        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? {
            if (!spec.timeOfDay.isNullOrBlank()) return 24L * 60 * 60 * 1000
            val cron = spec.cron?.trim() ?: return null
            // @every Ns
            val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
            if (every != null) {
                val n = every.groupValues[1].toLong()
                val unit = every.groupValues[2]
                return when (unit) {
                    "s" -> n * 1000
                    "m" -> n * 60 * 1000
                    "h" -> n * 60 * 60 * 1000
                    "d" -> n * 24 * 60 * 60 * 1000
                    else -> null
                }
            }
            return when (cron) {
                "@hourly" -> 60L * 60 * 1000
                "@daily", "@midnight" -> 24L * 60 * 60 * 1000
                "@weekly" -> 7L * 24 * 60 * 60 * 1000
                else -> null  // 5-field cron — fall back to one-shot
            }
        }

        /** Compute the next fire time. For unsupported cron forms, returns now+15 min. */
        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            // time_of_day + optional days_of_week
            if (!spec.timeOfDay.isNullOrBlank()) {
                val (h, m) = spec.timeOfDay.split(":").let { it[0].toInt() to it[1].toInt() }
                var candidate = now.toLocalDate().atTime(LocalTime.of(h, m)).atZone(zone)
                if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
                if (spec.daysOfWeek.isNotEmpty()) {
                    val allowed = spec.daysOfWeek.map { isoDow(it) }.toSet()
                    var hops = 0
                    while (candidate.dayOfWeek !in allowed && hops < 8) {
                        candidate = candidate.plusDays(1); hops++
                    }
                }
                return candidate.toInstant().toEpochMilli()
            }
            // @every Ns
            derivePeriodMs(spec)?.let { return nowMs + it }
            // Fallback for unsupported cron — fire roughly hourly so the user gets
            // something useful even with an exotic schedule. Worker will re-evaluate.
            return nowMs + 60L * 60 * 1000
        }

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
