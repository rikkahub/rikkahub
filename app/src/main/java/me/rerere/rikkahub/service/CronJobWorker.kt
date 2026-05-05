package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CronJobWorker"

/**
 * Process-scoped tracker of cron jobs whose worker is currently running. Prevents two
 * concurrent CronJobWorkers from racing on the same job — without this, a manual
 * re-schedule (pause/resume, user-triggered re-fire) while a previous run is still in
 * its LLM turn could enqueue a near-zero-delay second run, producing two parallel
 * generations writing to the same job's `lastRunAtMs`. The set lives only in this
 * process; if WorkManager respawns the process while a job was running, the tracker
 * resets cleanly (the killed worker is also gone).
 */
private object CronJobRunningTracker {
    private val running = ConcurrentHashMap.newKeySet<String>()
    /** Returns true if this caller successfully claimed the running flag. */
    fun start(jobId: String): Boolean = running.add(jobId)
    fun stop(jobId: String) {
        running.remove(jobId)
    }
}

/**
 * Worker that fires when a scheduled job's time has come.
 *
 * Behavior: load the job, look up the assistant, create a new Conversation seeded with the
 * job's prompt as a user message, hand off to ChatService.sendMessage to drive the existing
 * generation pipeline (tools and all). Post a notification so the user knows the job ran.
 *
 * After running, persist lastRunAtMs and (for interval jobs) re-schedule the next fire.
 */
class CronJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val repo: ScheduledJobRepository by inject()
    private val scheduler: CronJobScheduler by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()

        // Refuse to run two CronJobWorkers for the same job concurrently. A spurious
        // second enqueue (pause/resume race, user-triggered re-fire while previous run
        // is mid-LLM) is silently dropped instead of producing two parallel writes to
        // lastRunAtMs and two parallel LLM turns racing for the same prompt.
        if (!CronJobRunningTracker.start(jobId)) {
            Log.i(TAG, "doWork: $jobId already running; skipping this fire")
            return Result.success()
        }
        try {
            val job = repo.getById(jobId) ?: return Result.success()
            if (!job.enabled) return Result.success()

            var convId: Uuid? = null
            try {
                runCatching {
                    val assistantUuid = Uuid.parse(job.assistantId)
                    val conv = Conversation.ofId(
                        id = Uuid.random(),
                        assistantId = assistantUuid,
                        newConversation = true
                    ).copy(title = "[Scheduled] ${job.name}")
                    convId = conv.id
                    conversationRepo.insertConversation(conv)
                    chatService.initializeConversation(conv.id)
                    // Mark the conversation as headless so ChatService's auto-approval check
                    // returns true for every tool. The user pre-authorised this at job-create
                    // time (the schedule_job approval prompt warned them); without this mark,
                    // every approval-gated tool flips to Pending and the worker stalls
                    // forever with no UI surface to grant approval.
                    HeadlessConversations.mark(conv.id)
                    chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(job.prompt)))

                    // Best-effort: wait briefly so the worker stays alive long enough for ChatService
                    // to take ownership of the generation. Bounded by the worker's overall wall
                    // budget below — without that, a hung headless flag (e.g. a glitch in the
                    // isHeadless check) would stall the worker indefinitely.
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(15L * 60_000L) {
                            chatService.getGenerationJobStateFlow(conv.id).first { it == null }
                        }
                    } catch (_: Throwable) {
                        // ignore — generation either finished or was cancelled
                    }

                    // No success notification: the cron prompt itself is responsible for whatever the
                    // user wants to see (post_notification, telegram_send_message, etc). A blanket
                    // "Scheduled job ran" on top of that produces a duplicate ping which the user
                    // hits as a confusing two-notification bug. Failure path still notifies because
                    // otherwise a silently-broken cron would have zero feedback.
                }.onFailure {
                    postNotification(job.name, errorMessage = it.message ?: it::class.simpleName ?: "error")
                }
            } finally {
                // Unmark in finally so a worker death (low-memory kill, surprise CancellationException
                // from WorkManager) doesn't leak the headless mark for the conv id. Conv ids
                // are unique per fire so leaks are harmless functionally, but the set should
                // shrink rather than only grow.
                convId?.let { HeadlessConversations.unmark(it) }
            }

            // Update lastRunAtMs and reschedule if interval-based.
            val nowMs = System.currentTimeMillis()
            val updated = job.copy(
                lastRunAtMs = nowMs,
                enabled = if (job.scheduleType == "once") false else job.enabled,
            )
            repo.update(updated)
            if (updated.enabled) {
                scheduler.schedule(updated)
            }
            return Result.success()
        } finally {
            CronJobRunningTracker.stop(jobId)
        }
    }

    private fun postNotification(jobName: String, conversationId: String? = null, errorMessage: String? = null) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Scheduled jobs", NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
        val title = if (errorMessage == null) "Scheduled job ran" else "Scheduled job failed"
        val text = if (errorMessage == null) jobName else "$jobName: $errorMessage"
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(ctx).notify(jobName.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silent failure is fine
        }
    }

    companion object {
        const val KEY_JOB_ID = "cron_job_id"
        const val CHANNEL_ID = "rikkahub_cron_jobs"
    }
}
