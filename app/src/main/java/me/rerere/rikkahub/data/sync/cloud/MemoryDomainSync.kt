package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.utils.JsonInstant

class MemoryDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val memoryDAO: MemoryDAO,
) {
    suspend fun enqueueUpsert(entity: MemoryEntity) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_MEMORY, entity.id)?.revision ?: 0L
        val payload = buildJsonObject {
            put(
                "payload",
                buildJsonObject {
                    put("assistant_id", JsonPrimitive(entity.assistantId))
                    put("content", JsonPrimitive(entity.content))
                },
            )
        }
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_MEMORY,
            entityId = entity.id,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued memory upsert ${entity.id}")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun enqueueDelete(memoryId: String) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_MEMORY, memoryId)?.revision ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_MEMORY,
            entityId = memoryId,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued memory delete $memoryId")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun seedLocalMemories() {
        for (entity in memoryDAO.getAllMemories()) {
            if (revisionDao.get(ENTITY_MEMORY, entity.id) != null) continue
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
            memoryDAO.deleteMemory(entityId)
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
        val content = str("content").orEmpty()
        memoryDAO.insertMemory(
            MemoryEntity(
                id = entityId,
                assistantId = assistantId,
                content = content,
            )
        )
        rememberRevision(entityId, revision)
    }

    private suspend fun rememberRevision(entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = ENTITY_MEMORY,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "MemoryDomainSync"
        const val ENTITY_MEMORY = "assistant_memory"
    }
}
