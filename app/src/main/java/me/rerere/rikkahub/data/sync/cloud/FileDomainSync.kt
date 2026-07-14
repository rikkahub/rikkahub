package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID

class FileDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val filesRepository: FilesRepository,
) {
    suspend fun onLocalUpsert(entity: ManagedFileEntity) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        if (entity.deletedAt != null) return
        // Only enqueue metadata once we have a stable local file; bytes upload is separate.
        val status = when (entity.uploadStatus) {
            ManagedFileEntity.UPLOAD_LOCAL_ONLY,
            ManagedFileEntity.UPLOAD_PENDING,
            ManagedFileEntity.UPLOAD_UPLOADING,
            ManagedFileEntity.UPLOAD_READY,
            ManagedFileEntity.UPLOAD_FAILED,
            -> entity.uploadStatus
            else -> ManagedFileEntity.UPLOAD_LOCAL_ONLY
        }
        val forSync = if (status == ManagedFileEntity.UPLOAD_LOCAL_ONLY) {
            entity.copy(uploadStatus = ManagedFileEntity.UPLOAD_PENDING)
        } else {
            entity
        }
        if (status == ManagedFileEntity.UPLOAD_LOCAL_ONLY) {
            filesRepository.upsertQuiet(forSync)
        }
        enqueueUpsert(forSync)
        cloudSyncRepository.requestFileTransfer()
    }

    suspend fun onLocalDelete(fileId: String) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        enqueueDelete(fileId)
    }

    suspend fun enqueueUpsert(entity: ManagedFileEntity) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_FILE, entity.id)?.revision ?: entity.remoteRevision
        val payload = buildJsonObject {
            put(
                "payload",
                buildJsonObject {
                    put("folder", JsonPrimitive(entity.folder))
                    put("display_name", JsonPrimitive(entity.displayName))
                    put("mime_type", JsonPrimitive(entity.mimeType))
                    put("size_bytes", JsonPrimitive(entity.sizeBytes))
                    put("sha256", JsonPrimitive(entity.sha256))
                    put("object_key", JsonPrimitive(entity.objectKey))
                    put(
                        "upload_status",
                        JsonPrimitive(
                            when (entity.uploadStatus) {
                                ManagedFileEntity.UPLOAD_READY -> "ready"
                                ManagedFileEntity.UPLOAD_FAILED -> "failed"
                                ManagedFileEntity.UPLOAD_DELETED -> "deleted"
                                else -> "pending"
                            }
                        ),
                    )
                },
            )
        }
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_FILE,
            entityId = entity.id,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued file upsert ${entity.id}")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun enqueueDelete(fileId: String) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_FILE, fileId)?.revision ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_FILE,
            entityId = fileId,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued file delete $fileId")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun seedLocalFiles() {
        for (entity in filesRepository.getAll()) {
            if (revisionDao.get(ENTITY_FILE, entity.id) != null) continue
            if (entity.deletedAt != null) continue
            enqueueUpsert(
                if (entity.uploadStatus == ManagedFileEntity.UPLOAD_LOCAL_ONLY) {
                    entity.copy(uploadStatus = ManagedFileEntity.UPLOAD_PENDING)
                } else {
                    entity
                }
            )
        }
        cloudSyncRepository.requestFileTransfer()
    }

    suspend fun applyRemotePayload(
        entityId: String,
        operation: String,
        payload: JsonElement?,
        revision: Long,
    ) {
        if (operation == "delete") {
            val existing = filesRepository.getById(entityId)
            if (existing != null) {
                filesRepository.upsertQuiet(
                    existing.copy(
                        uploadStatus = ManagedFileEntity.UPLOAD_DELETED,
                        deletedAt = System.currentTimeMillis(),
                        remoteRevision = revision,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            rememberRevision(entityId, revision)
            return
        }
        val root = payload as? JsonObject ?: return
        val body = (root["payload"] as? JsonObject) ?: root
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = body[k] ?: root[k]
                val p = v as? JsonPrimitive ?: continue
                if (v is JsonNull) continue
                return p.contentOrNull
            }
            return null
        }
        fun long(vararg keys: String): Long? {
            for (k in keys) {
                val v = body[k] ?: root[k]
                val p = v as? JsonPrimitive ?: continue
                p.longOrNull?.let { return it }
                p.contentOrNull?.toLongOrNull()?.let { return it }
            }
            return null
        }
        val folder = str("folder") ?: "upload"
        val displayName = str("display_name", "displayName") ?: "file"
        val mimeType = str("mime_type", "mimeType") ?: "application/octet-stream"
        val sizeBytes = long("size_bytes", "sizeBytes") ?: 0L
        val sha256 = str("sha256").orEmpty()
        val objectKey = str("object_key", "objectKey").orEmpty()
        val remoteStatus = str("upload_status", "uploadStatus") ?: "pending"
        val existing = filesRepository.getById(entityId)
        val now = System.currentTimeMillis()
        val id = runCatching { UUID.fromString(entityId).toString() }.getOrElse { entityId }
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "bin")
            .ifBlank { "bin" }
        val localPath = existing?.relativePath ?: "$folder/$id.$ext"
        // Lazy: keep remote metadata only; download when UI asks (CloudMediaResolver).
        val hadLocalBytes = existing?.uploadStatus == ManagedFileEntity.UPLOAD_READY
        val keepPendingDownload =
            existing?.uploadStatus == ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD
        val entity = ManagedFileEntity(
            id = id,
            folder = folder,
            relativePath = localPath,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            sha256 = sha256,
            objectKey = objectKey,
            // Never mark remote metadata as local UPLOAD_PENDING — B would try to
            // re-upload bytes it does not have and spam "正在上传".
            uploadStatus = when {
                keepPendingDownload -> ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD
                hadLocalBytes && remoteStatus == "ready" -> ManagedFileEntity.UPLOAD_READY
                hadLocalBytes && remoteStatus != "ready" -> existing?.uploadStatus
                    ?: ManagedFileEntity.UPLOAD_LOCAL_ONLY
                remoteStatus == "ready" -> ManagedFileEntity.UPLOAD_REMOTE_ONLY
                remoteStatus == "failed" -> ManagedFileEntity.UPLOAD_FAILED
                else -> ManagedFileEntity.UPLOAD_REMOTE_ONLY
            },
            remoteRevision = revision,
            deletedAt = null,
        )
        filesRepository.upsertQuiet(entity)
        rememberRevision(entityId, revision)
        // Do not auto-download all remote files after bootstrap/sync.
    }

    private suspend fun rememberRevision(entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = ENTITY_FILE,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "FileDomainSync"
        const val ENTITY_FILE = "file"
    }
}
