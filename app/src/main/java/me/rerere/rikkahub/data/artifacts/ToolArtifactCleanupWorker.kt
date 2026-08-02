package me.rerere.rikkahub.data.artifacts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps artifact retention independent from application startup. */
class ToolArtifactCleanupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            FileToolArtifactStore(File(applicationContext.filesDir, ARTIFACT_DIRECTORY)).cleanup()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "tool-artifact-retention-cleanup"
        const val ARTIFACT_DIRECTORY = "tool_artifacts"
    }
}
