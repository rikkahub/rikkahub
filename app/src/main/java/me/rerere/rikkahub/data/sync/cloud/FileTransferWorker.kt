package me.rerere.rikkahub.data.sync.cloud

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.FilesRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.security.MessageDigest

/**
 * Uploads/downloads file bytes only through Perry proxy.
 * MinIO stays private on the server; the app never receives storage credentials.
 */
class FileTransferWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val filesRepository: FilesRepository by inject()
    private val filesManager: FilesManager by inject()
    private val cloudSyncRepository: CloudSyncRepository by inject()
    private val uploadProgressTracker: UploadProgressTracker by inject()

    override suspend fun doWork(): Result {
        return try {
            // Drain all pending transfers in one worker run so the progress dialog
            // does not flicker between 8-file batches.
            var guard = 0
            while (guard < MAX_BATCHES) {
                guard++
                val moreUploads = processUploadBatch()
                val moreDownloads = processDownloadBatch()
                if (!moreUploads && !moreDownloads) break
            }
            uploadProgressTracker.clear()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "file transfer failed", e)
            Result.retry()
        }
    }

    /** @return true if more uploads remain after this batch */
    private suspend fun processUploadBatch(): Boolean {
        val statuses = listOf(
            ManagedFileEntity.UPLOAD_LOCAL_ONLY,
            ManagedFileEntity.UPLOAD_PENDING,
            ManagedFileEntity.UPLOAD_FAILED,
        )
        val pending = filesRepository.listByStatuses(statuses, limit = BATCH_LIMIT)
            // Only rows that actually have local bytes should enter upload progress UI.
            .filter { filesManager.getFile(it).let { f -> f.exists() && f.length() > 0L } }
        if (pending.isEmpty()) {
            // Clean ghost pending rows so they stop reappearing in the queue.
            val ghosts = filesRepository.listByStatuses(statuses, limit = BATCH_LIMIT)
            for (g in ghosts) {
                val f = filesManager.getFile(g)
                if (!f.exists() || f.length() <= 0L) {
                    filesRepository.upsertQuiet(
                        g.copy(
                            uploadStatus = if (g.remoteRevision > 0L || g.objectKey.isNotBlank()) {
                                ManagedFileEntity.UPLOAD_REMOTE_ONLY
                            } else {
                                ManagedFileEntity.UPLOAD_FAILED
                            },
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            return false
        }

        val remainingHint = filesRepository.listByStatuses(statuses, limit = 500)
            .count { filesManager.getFile(it).let { f -> f.exists() && f.length() > 0L } }
        if (remainingHint <= 0) return false
        uploadProgressTracker.setQueue(remaining = remainingHint, isDownload = false)

        for (entity in pending) {
            uploadOne(entity)
        }
        return filesRepository.listByStatuses(statuses, limit = 8)
            .any { filesManager.getFile(it).let { f -> f.exists() && f.length() > 0L } }
    }

    /** @return true if more downloads remain after this batch */
    private suspend fun processDownloadBatch(): Boolean {
        val statuses = listOf(ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD)
        val pending = filesRepository.listByStatuses(statuses, limit = BATCH_LIMIT)
        if (pending.isEmpty()) return false

        val remainingHint = filesRepository.listByStatuses(statuses, limit = 500).size
        uploadProgressTracker.setQueue(remaining = remainingHint, isDownload = true)

        for (entity in pending) {
            downloadOne(entity)
        }
        return filesRepository.listByStatuses(statuses, limit = 1).isNotEmpty()
    }

    private suspend fun uploadOne(entity: ManagedFileEntity) {
        val file = filesManager.getFile(entity)
        if (!file.exists() || file.length() <= 0L) {
            // Ghost rows from remote apply / stale import: never "upload", demote to remote_only.
            Log.w(TAG, "skip upload missing local bytes ${entity.id}; mark remote_only")
            filesRepository.upsertQuiet(
                entity.copy(
                    uploadStatus = if (entity.remoteRevision > 0L || entity.objectKey.isNotBlank()) {
                        ManagedFileEntity.UPLOAD_REMOTE_ONLY
                    } else {
                        ManagedFileEntity.UPLOAD_FAILED
                    },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return
        }
        val client = cloudSyncRepository.createAuthenticatedClient() ?: return
        val sha = entity.sha256.ifBlank { sha256Hex(file) }
        val size = file.length()
        uploadProgressTracker.beginFile(
            displayName = entity.displayName.ifBlank { entity.id },
            bytesTotal = size,
            isDownload = false,
        )
        filesRepository.upsertQuiet(
            entity.copy(
                sha256 = sha,
                sizeBytes = size,
                uploadStatus = ManagedFileEntity.UPLOAD_UPLOADING,
                updatedAt = System.currentTimeMillis(),
            )
        )
        try {
            val initDisplay = if (
                entity.folder == me.rerere.rikkahub.data.files.FileFolders.SKILLS &&
                entity.relativePath.startsWith("${me.rerere.rikkahub.data.files.FileFolders.SKILLS}/")
            ) {
                entity.relativePath.removePrefix(
                    "${me.rerere.rikkahub.data.files.FileFolders.SKILLS}/",
                )
            } else {
                entity.displayName
            }
            val init = client.initFile(
                FileInitRequest(
                    id = entity.id,
                    folder = entity.folder,
                    displayName = initDisplay,
                    mimeType = entity.mimeType,
                    sizeBytes = size,
                    sha256 = sha,
                )
            )
            if (init.uploadStatus != "ready") {
                client.putFileContent(
                    fileId = entity.id,
                    bytes = file.readBytes(),
                    mimeType = entity.mimeType,
                    onProgress = { sent, totalBytes ->
                        uploadProgressTracker.updateBytes(sent, totalBytes)
                    },
                )
                client.completeFile(
                    entity.id,
                    FileCompleteRequest(sizeBytes = size, sha256 = sha),
                )
            } else {
                uploadProgressTracker.updateBytes(size, size)
            }
            val ready = entity.copy(
                sha256 = sha,
                sizeBytes = size,
                objectKey = init.objectKey,
                uploadStatus = ManagedFileEntity.UPLOAD_READY,
                remoteRevision = init.revision.toLong(),
                updatedAt = System.currentTimeMillis(),
            )
            filesRepository.upsertQuiet(ready)
            cloudSyncRepository.fileDomainSync?.enqueueUpsert(ready)
            uploadProgressTracker.completeFile()
            Log.i(TAG, "uploaded file ${entity.id} via perry proxy")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Worker REPLACE/cancellation: leave as pending so next run retries.
            filesRepository.upsertQuiet(
                entity.copy(
                    sha256 = sha,
                    sizeBytes = size,
                    uploadStatus = ManagedFileEntity.UPLOAD_PENDING,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "upload failed ${entity.id}: ${e.message}")
            filesRepository.upsertQuiet(
                entity.copy(
                    sha256 = sha,
                    sizeBytes = size,
                    uploadStatus = ManagedFileEntity.UPLOAD_FAILED,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            throw e
        }
    }

    private suspend fun downloadOne(entity: ManagedFileEntity) {
        val client = cloudSyncRepository.createAuthenticatedClient() ?: return
        uploadProgressTracker.beginFile(
            displayName = entity.displayName.ifBlank { entity.id },
            bytesTotal = entity.sizeBytes.coerceAtLeast(0L),
            isDownload = true,
        )
        try {
            val target = filesManager.getFile(entity)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            val bytes = client.getFileContent(entity.id)
            uploadProgressTracker.updateBytes(bytes.size.toLong(), bytes.size.toLong())
            tmp.writeBytes(bytes)
            if (entity.sha256.isNotBlank()) {
                val actual = sha256Hex(tmp)
                if (!actual.equals(entity.sha256, ignoreCase = true)) {
                    tmp.delete()
                    error("sha256 mismatch for ${entity.id}")
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            filesRepository.upsertQuiet(
                entity.copy(
                    sizeBytes = target.length(),
                    uploadStatus = ManagedFileEntity.UPLOAD_READY,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            uploadProgressTracker.completeFile()
            Log.i(TAG, "downloaded file ${entity.id} via perry proxy")
        } catch (e: Exception) {
            Log.w(TAG, "download failed ${entity.id}: ${e.message}")
            filesRepository.upsertQuiet(
                entity.copy(
                    uploadStatus = ManagedFileEntity.UPLOAD_FAILED,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            throw e
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FileTransferWorker"
        private const val UNIQUE = "perry_file_transfer"
        private const val BATCH_LIMIT = 8
        private const val MAX_BATCHES = 64

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<FileTransferWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                // KEEP: REPLACE cancels in-flight uploads (JobCancellationException flood).
                // Worker already drains all pending batches in one run.
                ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
}
