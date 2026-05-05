package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Schedules / unschedules cron job executions via WorkManager. Each scheduled job has
 * a unique work name "cron_job_<id>" so we can deterministically replace or cancel its
 * pending run.
 *
 * Recurring jobs re-schedule themselves at the end of CronJobWorker.doWork(). Boot
 * recovery happens via CronBootReceiver which calls scheduleAllEnabled().
 */
class CronJobScheduler(
    private val context: Context,
    private val repo: ScheduledJobRepository,
) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun schedule(job: ScheduledJobEntity) {
        val nowMs = System.currentTimeMillis()
        val nextRun = nextRunMs(job, nowMs)
        repo.update(job.copy(nextRunAtMs = nextRun))
        if (nextRun == null) {
            cancel(job.id)
            return
        }
        val delayMs = max(0L, nextRun - nowMs)
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(CronJobWorker.KEY_JOB_ID, job.id).build())
            .build()
        wm.enqueueUniqueWork(workNameFor(job.id), ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Trigger a manual fire (trigger_job_now). Distinct work name from the regular schedule
     * + sets KEY_MANUAL=true so the worker skips lastRunAtMs / runs_so_far bumps. Manual
     * fires are bonus — they don't disturb the regular schedule's accounting.
     */
    suspend fun triggerNow(jobId: String) {
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putBoolean(CronJobWorker.KEY_MANUAL, true)
                .build())
            .build()
        wm.enqueueUniqueWork(manualWorkNameFor(jobId), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(jobId: String) {
        wm.cancelUniqueWork(workNameFor(jobId))
    }

    suspend fun scheduleAllEnabled() {
        repo.getEnabled().forEach { schedule(it) }
    }

    private fun workNameFor(jobId: String) = "cron_job_$jobId"
    private fun manualWorkNameFor(jobId: String) = "cron_job_${jobId}_manual"

    companion object {
        /**
         * Compute the next fire time given [nowMs]. Returns null if the job will never
         * fire again (disabled, max_runs reached, end_at past, once already fired).
         *
         * Pure function — no side effects, no Room access. Lives in the companion so
         * tests + the boot receiver can call it without instantiating a scheduler.
         */
        fun nextRunMs(job: ScheduledJobEntity, nowMs: Long): Long? {
            if (!job.enabled) return null
            if (job.maxRuns != null && job.runsSoFar >= job.maxRuns) return null
            if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return null

            return when (job.scheduleType) {
                "once" -> {
                    val at = job.atUnixMs ?: return null
                    if (job.lastRunAtMs != null) null else at
                }
                "cron" -> {
                    val expr = job.cronExpression ?: return null
                    val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                    val cron = CronExpressionParser.parse(expr).getOrNull() ?: return null
                    val basisMs = max(nowMs, job.startAtUnixMs ?: 0L) - 1L
                    val basisZdt = Instant.ofEpochMilli(basisMs).atZone(zone)
                    val nextZdt = CronExpressionParser.nextExecution(cron, basisZdt) ?: return null
                    val next = nextZdt.toInstant().toEpochMilli()
                    if (job.endAtUnixMs != null && next > job.endAtUnixMs) null else next
                }
                else -> null
            }
        }
    }
}
