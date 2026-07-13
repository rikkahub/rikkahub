package me.rerere.rikkahub.data.sync.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Phase 2 skeleton: push outbox then pull changes when network is available.
 * Full network client lands with settings UI (Phase 3+).
 */
class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val syncRepository: CloudSyncRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            val outcome = syncRepository.runSyncCycle()
            when (outcome) {
                CloudSyncOutcome.Success,
                CloudSyncOutcome.Skipped -> Result.success()
                is CloudSyncOutcome.Retryable -> Result.retry()
                is CloudSyncOutcome.Failed -> {
                    Result.failure(workDataOf("error" to outcome.message))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync cycle failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CloudSyncWorker"
        const val UNIQUE_WORK = "perry_cloud_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            // REPLACE: if a previous run is waiting, use the latest request so
            // recent outbox mutations are not stuck behind a dropped KEEP enqueue.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
