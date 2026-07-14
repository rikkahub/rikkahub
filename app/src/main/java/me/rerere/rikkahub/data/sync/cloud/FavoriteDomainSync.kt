package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.utils.JsonInstant

class FavoriteDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val favoriteDAO: FavoriteDAO,
) {
    suspend fun enqueueUpsert(entity: FavoriteEntity) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_FAVORITE, entity.id)?.revision ?: 0L
        val refEl = runCatching {
            JsonInstant.parseToJsonElement(entity.refJson)
        }.getOrDefault(JsonPrimitive(entity.refJson))
        val metaEl = entity.metaJson?.let {
            runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull()
        }
        val payload = buildJsonObject {
            put(
                "payload",
                buildJsonObject {
                    put("type", JsonPrimitive(entity.type))
                    put("ref_key", JsonPrimitive(entity.refKey))
                    put("ref_json", refEl)
                    put("snapshot_json", JsonPrimitive(entity.snapshotJson))
                    if (metaEl != null) put("meta_json", metaEl)
                    put("created_at_ms", JsonPrimitive(entity.createdAt))
                    put("updated_at_ms", JsonPrimitive(entity.updatedAt))
                },
            )
        }
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_FAVORITE,
            entityId = entity.id,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued favorite upsert ${entity.id}")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun enqueueDelete(favoriteId: String) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = revisionDao.get(ENTITY_FAVORITE, favoriteId)?.revision ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_FAVORITE,
            entityId = favoriteId,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued favorite delete $favoriteId")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun seedLocalFavorites() {
        for (entity in favoriteDAO.getAll()) {
            if (revisionDao.get(ENTITY_FAVORITE, entity.id) != null) continue
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
            favoriteDAO.deleteById(entityId)
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

        fun long(vararg keys: String): Long = str(*keys)?.toLongOrNull() ?: 0L

        fun jsonField(vararg keys: String): String {
            for (k in keys) {
                val v = body[k] ?: root[k] ?: continue
                if (v is JsonNull) continue
                return when (v) {
                    is JsonPrimitive -> v.contentOrNull ?: "{}"
                    else -> JsonInstant.encodeToString(v)
                }
            }
            return "{}"
        }

        fun optionalJsonField(vararg keys: String): String? {
            for (k in keys) {
                val v = body[k] ?: root[k] ?: continue
                if (v is JsonNull) return null
                return when (v) {
                    is JsonPrimitive -> v.contentOrNull
                    else -> JsonInstant.encodeToString(v)
                }
            }
            return null
        }

        val type = str("type") ?: "node"
        val refKey = str("ref_key", "refKey") ?: return
        val refJson = jsonField("ref_json", "refJson")
        val snapshotJson = str("snapshot_json", "snapshotJson").orEmpty()
        val metaJson = optionalJsonField("meta_json", "metaJson")
        val createdAt = long("created_at_ms", "createdAt")
        val updatedAt = long("updated_at_ms", "updatedAt").let { if (it == 0L) createdAt else it }

        // Drop other local row with same ref_key (server wins).
        val existingByRef = favoriteDAO.getByRefKey(refKey)
        if (existingByRef != null && existingByRef.id != entityId) {
            favoriteDAO.deleteById(existingByRef.id)
        }
        favoriteDAO.upsert(
            FavoriteEntity(
                id = entityId,
                type = type,
                refKey = refKey,
                refJson = refJson,
                snapshotJson = snapshotJson,
                metaJson = metaJson,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        )
        rememberRevision(entityId, revision)
    }

    private suspend fun rememberRevision(entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = ENTITY_FAVORITE,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "FavoriteDomainSync"
        const val ENTITY_FAVORITE = "favorite"
    }
}
