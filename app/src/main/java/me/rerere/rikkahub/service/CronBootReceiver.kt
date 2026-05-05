package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Re-schedules every enabled cron job after device reboot or app upgrade. Also:
 *  - applies catchup policy for windows missed during downtime
 *  - flips stranded run rows (started_at_ms in the past with no finished_at_ms) to
 *    'process_killed_replay' so get_job_history shows the truth
 */
class CronBootReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: CronJobScheduler by inject()
    private val repo: ScheduledJobRepository by inject()
    private val runRepo: ScheduledJobRunRepository by inject()
    private val telegramPrefs: me.rerere.rikkahub.data.telegram.TelegramBotPreferences by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        val pending = goAsync()
        scope.launch {
            try {
                sweepStrandedRunRows(context)
                applyCatchupAndSchedule(context)

                // Re-start Telegram bot if it was enabled (existing behavior).
                val cfg = try { telegramPrefs.current() } catch (_: Throwable) { null }
                if (cfg != null && cfg.isUsable) {
                    me.rerere.rikkahub.service.TelegramBotService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun sweepStrandedRunRows(context: Context) {
        val cutoff = System.currentTimeMillis() - 30L * 60_000L
        val stranded = runRepo.getStranded(stalenessMs = cutoff)
        for (row in stranded) {
            runRepo.update(row.copy(
                finishedAtMs = System.currentTimeMillis(),
                outcome = "process_killed_replay",
                errorMessage = "worker terminated mid-execute",
            ))
            // Per spec §6 notification policy: process_killed_replay posts a failure notification
            // at detection time so the user knows their cron silently died.
            val job = repo.getById(row.jobId) ?: continue
            postFailureNotification(context, job.name, "process_killed_replay: worker terminated mid-execute")
        }
    }

    private fun postFailureNotification(ctx: Context, jobName: String, errorMessage: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CronJobWorker.CHANNEL_ID) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(
                CronJobWorker.CHANNEL_ID, "Scheduled jobs",
                android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = androidx.core.app.NotificationCompat.Builder(ctx, CronJobWorker.CHANNEL_ID)
            .setContentTitle("Scheduled job failed")
            .setContentText("$jobName: $errorMessage")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$jobName: $errorMessage"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(jobName.hashCode(), builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

    private suspend fun applyCatchupAndSchedule(context: Context) {
        val nowMs = System.currentTimeMillis()
        val wm = WorkManager.getInstance(context)
        for (job in repo.getEnabled()) {
            // Compute catchup plan based on the job's last fire vs now.
            val plan = CatchupPlanner.plan(job, lastRunMs = job.lastRunAtMs, nowMs = nowMs)

            // Record skipped_catchup rows for windows we deliberately drop.
            for (i in 0 until plan.skippedCatchupCount) {
                runRepo.insert(ScheduledJobRunEntity(
                    id = kotlin.uuid.Uuid.random().toString(),
                    jobId = job.id,
                    mode = job.mode,
                    scheduledAtMs = nowMs,                  // we don't reconstruct each missed window's exact time
                    startedAtMs = nowMs,
                    finishedAtMs = nowMs,
                    outcome = "skipped_catchup",
                    conversationId = null,
                    errorMessage = null,
                ))
            }

            // Enqueue the catchup fires (mode + delay come from the planner).
            for ((idx, delayMs) in plan.fireDelaysMs.withIndex()) {
                val req = OneTimeWorkRequestBuilder<CronJobWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putString(CronJobWorker.KEY_JOB_ID, job.id).build())
                    .build()
                // Each catchup fire gets a distinct unique work name so they don't
                // REPLACE-cancel each other.
                wm.enqueueUniqueWork(
                    "cron_job_${job.id}_catchup_$idx",
                    ExistingWorkPolicy.REPLACE, req
                )
            }

            // Schedule the next REGULAR fire (does not interfere with catchups above).
            scheduler.schedule(job)
        }
    }
}
