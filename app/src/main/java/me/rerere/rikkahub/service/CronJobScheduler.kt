package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Schedules / unschedules cron job executions via WorkManager. Each scheduled job has a unique
 * work name "cron_job_<id>" so we can deterministically replace or cancel its pending run.
 *
 * Recurring jobs re-schedule themselves at the end of CronJobWorker.doWork(). Boot recovery
 * happens via CronBootReceiver which calls scheduleAllEnabled().
 */
class CronJobScheduler(
    private val context: Context,
    private val repo: ScheduledJobRepository,
) {
    private val wm get() = WorkManager.getInstance(context)

    /** Compute the next fire time for a job given now. Returns null if it'll never fire again. */
    fun computeNextRun(job: ScheduledJobEntity, nowMs: Long = System.currentTimeMillis()): Long? {
        if (!job.enabled) return null
        return when (job.scheduleType) {
            "once" -> {
                val at = job.atUnixMs ?: return null
                if (job.lastRunAtMs != null) null else at  // already ran
            }
            "interval" -> {
                val period = (job.intervalSeconds ?: return null) * 1000L
                val anchor = job.lastRunAtMs ?: nowMs
                // Next fire is anchor + period, but never in the past.
                max(anchor + period, nowMs + 5_000L)
            }
            else -> null
        }
    }

    /** Persist next-run, then enqueue a OneTimeWorkRequest with delay = next-run minus now. */
    suspend fun schedule(job: ScheduledJobEntity) {
        val nowMs = System.currentTimeMillis()
        val nextRun = computeNextRun(job, nowMs)
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

    fun cancel(jobId: String) {
        wm.cancelUniqueWork(workNameFor(jobId))
    }

    /** Re-enqueue every enabled job — called from CronBootReceiver and on app start. */
    suspend fun scheduleAllEnabled() {
        repo.getEnabled().forEach { schedule(it) }
    }

    private fun workNameFor(jobId: String) = "cron_job_$jobId"
}
