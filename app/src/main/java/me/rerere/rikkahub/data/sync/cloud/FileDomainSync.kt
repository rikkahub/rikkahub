package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import android.content.Context
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class FileDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val filesRepository: FilesRepository,
    private val appContext: Context,
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
        // Skills keep path in display_name as "skillName/relative/file" so B can rebuild path.
        val displayForSync = when {
            entity.folder == FileFolders.SKILLS &&
                entity.relativePath.startsWith("${FileFolders.SKILLS}/") ->
                entity.relativePath.removePrefix("${FileFolders.SKILLS}/")
            else -> entity.displayName
        }
        val payload = buildJsonObject {
            put(
                "payload",
                buildJsonObject {
                    put("folder", JsonPrimitive(entity.folder))
                    put("display_name", JsonPrimitive(displayForSync))
                    put("relative_path", JsonPrimitive(entity.relativePath))
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
        // Register on-disk skills as managed files first so they enter outbox.
        seedSkillsFromDisk()
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

    /**
     * Walk filesDir/skills and upsert managed_file rows (id = stable nameUUID of path).
     * Does not enqueue by itself; caller uses seedLocalFiles / onLocalUpsert.
     */
    suspend fun seedSkillsFromDisk(): Int {
        val root = File(appContext.filesDir, FileFolders.SKILLS)
        if (!root.isDirectory) return 0
        var n = 0
        val now = System.currentTimeMillis()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = "${FileFolders.SKILLS}/" +
                file.relativeTo(root).invariantSeparatorsPath
            if (filesRepository.getByPath(rel) != null) return@forEach
            val id = UUID.nameUUIDFromBytes(rel.toByteArray(Charsets.UTF_8)).toString()
            if (filesRepository.getById(id) != null) return@forEach
            val entity = ManagedFileEntity(
                id = id,
                folder = FileFolders.SKILLS,
                relativePath = rel,
                displayName = file.name,
                mimeType = guessMime(file.name),
                sizeBytes = file.length(),
                createdAt = file.lastModified().takeIf { it > 0L } ?: now,
                updatedAt = now,
                sha256 = sha256Hex(file),
                uploadStatus = ManagedFileEntity.UPLOAD_LOCAL_ONLY,
            )
            // insert() enqueues cloud mutation + file transfer
            filesRepository.insert(entity)
            n++
        }
        if (n > 0) Log.i(TAG, "seeded $n skill files into managed_files")
        return n
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
        val relativeFromPayload = str("relative_path", "relativePath")
        val existing = filesRepository.getById(entityId)
        val now = System.currentTimeMillis()
        val id = runCatching { UUID.fromString(entityId).toString() }.getOrElse { entityId }
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "bin")
            .ifBlank { "bin" }
        // Skills encode path as folder + display_name (skillName/SKILL.md).
        val localPath = existing?.relativePath
            ?: relativeFromPayload
            ?: when {
                folder == FileFolders.SKILLS && displayName.contains('/') ->
                    "$folder/$displayName"
                folder != "upload" && displayName.contains('/') ->
                    "$folder/$displayName"
                else -> "$folder/$id.$ext"
            }
        val shortDisplay = displayName.substringAfterLast('/').ifBlank { displayName }
        // Lazy: keep remote metadata only; download when UI asks (CloudMediaResolver).
        // Skills: auto-queue download so Agent Skills appear without opening each file.
        val hadLocalBytes = existing?.uploadStatus == ManagedFileEntity.UPLOAD_READY ||
            File(appContext.filesDir, localPath).let { it.exists() && it.length() > 0L }
        val keepPendingDownload =
            existing?.uploadStatus == ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD
        val autoDownloadSkills = folder == FileFolders.SKILLS && !hadLocalBytes
        val entity = ManagedFileEntity(
            id = id,
            folder = folder,
            relativePath = localPath,
            displayName = shortDisplay,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            sha256 = sha256,
            objectKey = objectKey,
            // Never mark remote metadata as local UPLOAD_PENDING / LOCAL_ONLY —
            // B would try to re-upload bytes it does not have and spam "正在上传".
            uploadStatus = when {
                keepPendingDownload || autoDownloadSkills ->
                    ManagedFileEntity.UPLOAD_PENDING_DOWNLOAD
                hadLocalBytes && remoteStatus == "ready" -> ManagedFileEntity.UPLOAD_READY
                hadLocalBytes -> existing?.uploadStatus
                    ?.takeUnless {
                        it == ManagedFileEntity.UPLOAD_PENDING ||
                            it == ManagedFileEntity.UPLOAD_UPLOADING ||
                            it == ManagedFileEntity.UPLOAD_FAILED
                    }
                    ?: ManagedFileEntity.UPLOAD_READY
                remoteStatus == "ready" || remoteStatus == "pending" ->
                    ManagedFileEntity.UPLOAD_REMOTE_ONLY
                remoteStatus == "failed" -> ManagedFileEntity.UPLOAD_FAILED
                else -> ManagedFileEntity.UPLOAD_REMOTE_ONLY
            },
            remoteRevision = revision,
            deletedAt = null,
        )
        filesRepository.upsertQuiet(entity)
        rememberRevision(entityId, revision)
        if (autoDownloadSkills || keepPendingDownload) {
            cloudSyncRepository.requestFileTransfer()
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".webp", true) -> "image/webp"
        else -> "application/octet-stream"
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
