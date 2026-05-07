package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent

/**
 * Phase 12 — WorkManager worker that fires a workflow on its time/cron schedule.
 *
 * Uses Koin component injection (same pattern as CronJobWorker for scheduled jobs). The
 * actual fire dispatch goes through [TriggerRegistry] so condition + cooldown evaluation
 * happens consistently with broadcast-driven fires.
 */
class WorkflowTimeCronWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val registry: TriggerRegistry by inject()

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(TimeCronTriggerFamily.KEY_WORKFLOW_ID) ?: return Result.failure()
        registry.fireFromTimeCronWorker(workflowId)
        return Result.success()
    }
}

/**
 * Helper that the time/cron family uses to fall back to a direct repository read when its
 * `lastSnapshot` hasn't been populated yet (post-boot race). Lives outside the family
 * itself so the family can stay free of Koin lookups.
 */
internal object TimeCronWorkerHelper {
    suspend fun repositoryLookup(workflowId: String): WorkflowRepository.Loaded? = runCatching {
        val repo = KoinJavaComponent.getKoin().get<WorkflowRepository>()
        repo.getById(workflowId)
    }.getOrNull()
}
