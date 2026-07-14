package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.sync.cloud.CloudSyncRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-first media resolution for chat/settings UI.
 *
 * 1. Prefer existing local file
 * 2. If only remote metadata exists, request a single-file download
 * 3. Never bulk-download all cloud files on sync
 */
class CloudMediaResolver(
    private val context: Context,
    private val filesRepository: FilesRepository,
    private val cloudSyncRepository: CloudSyncRepository,
) {
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /**
     * Resolve a model for Coil/AsyncImage. May return File, Uri string, or original URL.
     * Triggers on-demand download when local bytes are missing.
     */
    suspend fun resolveForDisplay(model: Any?): Any? = withContext(Dispatchers.IO) {
        when (model) {
            null -> null
            is File -> if (model.exists() && model.length() > 0L) model else null
            is Uri -> resolveString(model.toString())
            is String -> resolveString(model)
            else -> model
        }
    }

    /**
     * Ensure a managed file is present on disk; queue download if needed.
     * @return local File when ready, null if still downloading / unavailable.
     */
    suspend fun ensureLocal(entity: ManagedFileEntity): File? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, entity.relativePath)
        if (file.exists() && file.length() > 0L) {
            if (entity.uploadStatus == ManagedFileEntity.UPLOAD_REMOTE_ONLY ||
                entity.uploadStatus == ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD
            ) {
                filesRepository.upsertQuiet(
                    entity.copy(
                        uploadStatus = ManagedFileEntity.UPLOAD_READY,
                        sizeBytes = file.length(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            return@withContext file
        }
        requestDownload(entity)
        null
    }

    suspend fun ensureLocalById(fileId: String): File? {
        val entity = filesRepository.getById(fileId) ?: return null
        return ensureLocal(entity)
    }

    private suspend fun resolveString(raw: String): Any? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        // Network URLs stay as-is (Coil HTTP cache).
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
            return value
        }

        // perry-file://{uuid}
        if (value.startsWith(PERRY_FILE_SCHEME)) {
            val id = value.removePrefix(PERRY_FILE_SCHEME).substringBefore('?').trim('/')
            val entity = filesRepository.getById(id)
            if (entity != null) {
                return ensureLocal(entity) ?: value
            }
            return value
        }

        // Local file URI / absolute path
        if (value.startsWith("file:") || value.startsWith("/")) {
            val local = runCatching {
                if (value.startsWith("file:")) value.toUri().toFile() else File(value)
            }.getOrNull()
            if (local != null && local.exists() && local.length() > 0L) {
                return local
            }
            // Path may be from another device; try map by filename UUID / relative upload path.
            val byPath = mapForeignPath(value)
            if (byPath != null) {
                return ensureLocal(byPath) ?: value
            }
            return value
        }

        // Bare managed file id
        runCatching { UUID.fromString(value) }.getOrNull()?.let { uuid ->
            val entity = filesRepository.getById(uuid.toString())
            if (entity != null) {
                return ensureLocal(entity) ?: value
            }
        }
        return value
    }

    private suspend fun mapForeignPath(value: String): ManagedFileEntity? {
        val path = value
            .removePrefix("file://")
            .substringAfter("files/", missingDelimiterValue = "")
            .ifBlank {
                value.substringAfter("/$UPLOAD/", missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
                    ?.let { "$UPLOAD/$it" }
                    .orEmpty()
            }
            .trimStart('/')
        if (path.isBlank()) {
            // try last path segment as uuid.ext
            val name = value.substringAfterLast('/').substringBefore('?')
            val stem = name.substringBeforeLast('.', missingDelimiterValue = name)
            runCatching { UUID.fromString(stem) }.getOrNull()?.let { uuid ->
                return filesRepository.getById(uuid.toString())
                    ?: filesRepository.getByPath("$UPLOAD/$name")
            }
            return null
        }
        filesRepository.getByPath(path)?.let { return it }
        val name = path.substringAfterLast('/')
        val stem = name.substringBeforeLast('.', missingDelimiterValue = name)
        runCatching { UUID.fromString(stem) }.getOrNull()?.let { uuid ->
            return filesRepository.getById(uuid.toString())
        }
        return null
    }

    private suspend fun requestDownload(entity: ManagedFileEntity) {
        if (entity.uploadStatus == ManagedFileEntity.UPLOAD_DELETED) return
        if (entity.deletedAt != null) return
        // Only remote-ready metadata should be downloaded.
        if (entity.uploadStatus == ManagedFileEntity.UPLOAD_PENDING ||
            entity.uploadStatus == ManagedFileEntity.UPLOAD_UPLOADING ||
            entity.uploadStatus == ManagedFileEntity.UPLOAD_LOCAL_ONLY
        ) {
            return
        }
        if (!requested.add(entity.id)) {
            // Already requested this process; still nudge worker.
            cloudSyncRepository.requestFileTransfer()
            return
        }
        Log.d(TAG, "on-demand download ${entity.id}")
        filesRepository.upsertQuiet(
            entity.copy(
                uploadStatus = ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD,
                updatedAt = System.currentTimeMillis(),
            )
        )
        cloudSyncRepository.requestFileTransfer()
    }

    companion object {
        private const val TAG = "CloudMediaResolver"
        private const val UPLOAD = "upload"
        const val PERRY_FILE_SCHEME = "perry-file://"
    }
}
