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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.security.MessageDigest

/**
 * Uploads pending local files and downloads remote-ready files missing on disk.
 */
class FileTransferWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val filesRepository: FilesRepository by inject()
    private val filesManager: FilesManager by inject()
    private val cloudSyncRepository: CloudSyncRepository by inject()
    private val okHttpClient: OkHttpClient by inject()

    override suspend fun doWork(): Result {
        return try {
            processUploads()
            processDownloads()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "file transfer failed", e)
            Result.retry()
        }
    }

    private suspend fun processUploads() {
        val pending = filesRepository.listByStatuses(
            listOf(
                ManagedFileEntity.UPLOAD_LOCAL_ONLY,
                ManagedFileEntity.UPLOAD_PENDING,
                ManagedFileEntity.UPLOAD_FAILED,
            ),
            limit = 8,
        )
        for (entity in pending) {
            uploadOne(entity)
        }
    }

    private suspend fun processDownloads() {
        val pending = filesRepository.listByStatuses(
            listOf(ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD),
            limit = 8,
        )
        for (entity in pending) {
            downloadOne(entity)
        }
    }

    private suspend fun uploadOne(entity: ManagedFileEntity) {
        val file = filesManager.getFile(entity)
        if (!file.exists() || file.length() <= 0L) {
            Log.w(TAG, "skip upload missing file ${entity.id}")
            return
        }
        val client = cloudSyncRepository.createAuthenticatedClient() ?: return
        val sha = entity.sha256.ifBlank { sha256Hex(file) }
        val size = file.length()
        filesRepository.upsertQuiet(
            entity.copy(
                sha256 = sha,
                sizeBytes = size,
                uploadStatus = ManagedFileEntity.UPLOAD_UPLOADING,
                updatedAt = System.currentTimeMillis(),
            )
        )
        try {
            val init = client.initFile(
                FileInitRequest(
                    id = entity.id,
                    folder = entity.folder,
                    displayName = entity.displayName,
                    mimeType = entity.mimeType,
                    sizeBytes = size,
                    sha256 = sha,
                )
            )
            if (init.uploadStatus != "ready") {
                val uploadUrl = init.uploadUrl
                if (!uploadUrl.isNullOrBlank() && !uploadUrl.contains("__memory_put__")) {
                    val body = file.asRequestBody(entity.mimeType.toMediaTypeOrNull())
                    val req = Request.Builder().url(uploadUrl).put(body).build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("presign PUT failed HTTP ${resp.code}")
                        }
                    }
                } else {
                    // Dev/test storage or unreachable MinIO: proxy through Perry.
                    client.putFileContent(entity.id, file.readBytes(), entity.mimeType)
                }
                client.completeFile(
                    entity.id,
                    FileCompleteRequest(sizeBytes = size, sha256 = sha),
                )
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
            Log.i(TAG, "uploaded file ${entity.id}")
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
        try {
            val target = filesManager.getFile(entity)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            val urlResp = client.downloadUrl(entity.id)
            val req = Request.Builder().url(urlResp.downloadUrl).get().build()
            val usedPresign = !urlResp.downloadUrl.contains("__memory_get__")
            val bytes = if (usedPresign) {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("download failed HTTP ${resp.code}")
                    resp.body?.bytes() ?: error("empty body")
                }
            } else {
                client.getFileContent(entity.id)
            }
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
            Log.i(TAG, "downloaded file ${entity.id}")
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

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<FileTransferWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
}
