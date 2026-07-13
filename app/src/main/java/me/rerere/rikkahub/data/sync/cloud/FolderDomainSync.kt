package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.utils.JsonInstant

class FolderDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val folderDAO: FolderDAO,
) {
    suspend fun enqueueUpsert(entity: FolderEntity) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_FOLDER, entity.id)?.revision ?: 0L
        val payload = buildJsonObject {
            put(
                "payload",
                buildJsonObject {
                    put("assistant_id", JsonPrimitive(entity.assistantId))
                    put("name", JsonPrimitive(entity.name))
                    put("sort_index", JsonPrimitive(entity.sortIndex))
                    put("create_at_ms", JsonPrimitive(entity.createAt))
                },
            )
        }
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_FOLDER,
            entityId = entity.id,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued folder upsert ${entity.id}")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun enqueueDelete(folderId: String) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_FOLDER, folderId)?.revision ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_FOLDER,
            entityId = folderId,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued folder delete $folderId")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun seedLocalFolders() {
        for (entity in folderDAO.getAll()) {
            if (revisionDao.get(ENTITY_FOLDER, entity.id) != null) continue
            enqueueUpsert(entity)
        }
    }

    suspend fun applyRemotePayload(
        entityId: String,
        operation: String,
        payload: JsonElement?,
        revision: Long,
    ) {
        if (operation == "delete") {
            folderDAO.deleteById(entityId)
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
        val assistantId = str("assistant_id", "assistantId") ?: return
        val name = str("name").orEmpty()
        val sortIndex = str("sort_index", "sortIndex")?.toIntOrNull() ?: 0
        val createAt = str("create_at_ms", "createAt")?.toLongOrNull() ?: 0L
        folderDAO.insert(
            FolderEntity(
                id = entityId,
                assistantId = assistantId,
                name = name,
                sortIndex = sortIndex,
                createAt = createAt,
            )
        )
        rememberRevision(entityId, revision)
    }

    private suspend fun rememberRevision(entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = ENTITY_FOLDER,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "FolderDomainSync"
        const val ENTITY_FOLDER = "conversation_folder"
    }
}
