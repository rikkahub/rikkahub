package me.rerere.rikkahub.data.sync.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
    private val manager: BackupAutomationManager,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            manager.runPeriodicIfNeeded()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}

