package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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
        val job = repo.getById(jobId) ?: return Result.success()
        if (!job.enabled) return Result.success()

        runCatching {
            val assistantUuid = Uuid.parse(job.assistantId)
            val conv = Conversation.ofId(
                id = Uuid.random(),
                assistantId = assistantUuid,
                newConversation = true
            ).copy(title = "[Scheduled] ${job.name}")
            conversationRepo.insertConversation(conv)
            chatService.initializeConversation(conv.id)
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(job.prompt)))

            // Best-effort: wait briefly so the worker stays alive long enough for ChatService
            // to take ownership of the generation. ChatService runs the actual LLM call in its
            // own coroutine; we don't block forever here.
            try {
                chatService.getGenerationJobStateFlow(conv.id).first { it == null }
            } catch (_: Throwable) {
                // ignore — generation either finished or was cancelled
            }

            postNotification(job.name, conv.id.toString())
        }.onFailure {
            postNotification(job.name, errorMessage = it.message ?: it::class.simpleName ?: "error")
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
