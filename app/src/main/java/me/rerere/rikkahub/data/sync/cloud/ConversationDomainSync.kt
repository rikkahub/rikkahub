package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Conversation summary domain for Phase 5 (no message nodes).
 */
class ConversationDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val conversationDAO: ConversationDAO,
) {
    suspend fun enqueueUpsert(entity: ConversationEntity) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        if (!entity.syncEnabled) return
        val rev = revisionDao.get(ENTITY_CONVERSATION, entity.id)?.revision
            ?: entity.remoteRevision
        val payload = buildMutationPayload(entity)
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_CONVERSATION,
            entityId = entity.id,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued conversation upsert ${entity.id} rev=$rev")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun enqueueDelete(conversationId: String, baseRevision: Long? = null) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = baseRevision
            ?: revisionDao.get(ENTITY_CONVERSATION, conversationId)?.revision
            ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_CONVERSATION,
            entityId = conversationId,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued conversation delete $conversationId rev=$rev")
            cloudSyncRepository.requestSync()
        }
    }

    suspend fun seedLocalConversations() {
        // Best-effort: push all local sync-enabled conversations that have no remote revision.
        val ids = conversationDAO.getAllIds()
        var enqueued = 0
        for (id in ids) {
            val known = revisionDao.get(ENTITY_CONVERSATION, id)
            if (known != null) continue
            val entity = conversationDAO.getConversationById(id) ?: continue
            if (!entity.syncEnabled || entity.deletedAt != null) continue
            enqueueUpsert(entity)
            enqueued++
        }
        if (enqueued > 0) {
            Log.d(TAG, "seeded $enqueued local conversations")
        }
    }

    suspend fun applyRemotePayload(
        entityId: String,
        operation: String,
        payload: JsonElement?,
        revision: Long,
    ) {
        if (operation == "delete") {
            conversationDAO.deleteById(entityId)
            rememberRevision(entityId, revision)
            return
        }
        val root = payload as? JsonObject ?: return
        val body = (root["payload"] as? JsonObject) ?: root
        val entity = summaryToEntity(entityId, root, body, revision) ?: return
        val existing = conversationDAO.getConversationById(entityId)
        if (existing != null) {
            conversationDAO.update(
                entity.copy(
                    // Preserve local-only workspace path and nodes column placeholder
                    nodes = existing.nodes,
                    workspaceCwd = existing.workspaceCwd,
                    lastAccessedAt = existing.lastAccessedAt,
                )
            )
        } else {
            conversationDAO.insert(entity)
        }
        rememberRevision(entityId, revision)
        Log.d(
            TAG,
            "applied remote conversation $entityId folder=${entity.folderId} title=${entity.title}",
        )
    }

    private fun summaryToEntity(
        entityId: String,
        root: JsonObject,
        body: JsonObject,
        revision: Long,
    ): ConversationEntity? {
        // Prefer server top-level columns, then nested payload body.
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = root[k] ?: body[k]
                val p = v as? JsonPrimitive ?: continue
                if (v is JsonNull) continue
                val content = p.contentOrNull ?: continue
                if (content.isNotEmpty()) return content
            }
            // Allow explicit empty string from top-level only (unfiled)
            for (k in keys) {
                val v = root[k] ?: body[k]
                val p = v as? JsonPrimitive ?: continue
                if (v is JsonNull) continue
                return p.contentOrNull
            }
            return null
        }

        fun long(vararg keys: String): Long {
            return str(*keys)?.toLongOrNull() ?: 0L
        }

        fun bool(vararg keys: String, default: Boolean): Boolean {
            for (k in keys) {
                val v = root[k] ?: body[k]
                val p = v as? JsonPrimitive ?: continue
                p.booleanOrNull?.let { return it }
                p.contentOrNull?.toBooleanStrictOrNull()?.let { return it }
            }
            return default
        }

        val assistantId = str("assistant_id", "assistantId") ?: return null
        val title = str("title") ?: ""
        val createAt = long("create_at_ms", "createAt")
        val updateAt = long("update_at_ms", "updateAt").let { if (it == 0L) createAt else it }
        val isPinned = bool("is_pinned", "isPinned", default = false)
        val folderId = str("folder_id", "folderId").orEmpty()
        val syncEnabled = bool("sync_enabled", "syncEnabled", default = true)
        val suggestions = body["chat_suggestions"] ?: body["chatSuggestions"]
        val suggestionsJson = when (suggestions) {
            is JsonArray -> JsonInstant.encodeToString(suggestions)
            null, is JsonNull -> "[]"
            else -> "[]"
        }
        val customPrompt = str("custom_system_prompt", "customSystemPrompt").orEmpty()
        val modeIds = body["mode_injection_ids"] ?: body["modeInjectionIds"]
        val loreIds = body["lorebook_ids"] ?: body["lorebookIds"]
        return ConversationEntity(
            id = entityId,
            assistantId = assistantId,
            title = title,
            nodes = "[]",
            createAt = createAt,
            updateAt = updateAt,
            chatSuggestions = suggestionsJson,
            isPinned = isPinned,
            customSystemPrompt = customPrompt,
            modeInjectionIds = encodeIdList(modeIds),
            lorebookIds = encodeIdList(loreIds),
            workspaceCwd = "",
            folderId = folderId,
            syncEnabled = syncEnabled,
            remoteRevision = revision,
            deletedAt = null,
            lastAccessedAt = 0,
        )
    }

    private fun encodeIdList(element: JsonElement?): String {
        if (element == null || element is JsonNull) return "[]"
        return runCatching { JsonInstant.encodeToString(element) }.getOrDefault("[]")
    }

    private fun buildMutationPayload(entity: ConversationEntity): JsonObject {
        val modeIds = runCatching {
            JsonInstant.decodeFromString<List<String>>(entity.modeInjectionIds)
        }.getOrDefault(emptyList())
        val loreIds = runCatching {
            JsonInstant.decodeFromString<List<String>>(entity.lorebookIds)
        }.getOrDefault(emptyList())
        val suggestions = runCatching {
            JsonInstant.decodeFromString<List<String>>(entity.chatSuggestions)
        }.getOrDefault(emptyList())
        val body = buildJsonObject {
            put("assistant_id", JsonPrimitive(entity.assistantId))
            put("title", JsonPrimitive(entity.title))
            put("create_at_ms", JsonPrimitive(entity.createAt))
            put("update_at_ms", JsonPrimitive(entity.updateAt))
            put("is_pinned", JsonPrimitive(entity.isPinned))
            put("folder_id", JsonPrimitive(entity.folderId))
            put("sync_enabled", JsonPrimitive(entity.syncEnabled))
            put(
                "chat_suggestions",
                buildJsonArray { suggestions.forEach { add(JsonPrimitive(it)) } },
            )
            put("custom_system_prompt", JsonPrimitive(entity.customSystemPrompt))
            put(
                "mode_injection_ids",
                buildJsonArray { modeIds.forEach { add(JsonPrimitive(it)) } },
            )
            put(
                "lorebook_ids",
                buildJsonArray { loreIds.forEach { add(JsonPrimitive(it)) } },
            )
        }
        return buildJsonObject {
            put("payload", body)
        }
    }

    private suspend fun rememberRevision(entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = ENTITY_CONVERSATION,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "ConversationDomainSync"
        const val ENTITY_CONVERSATION = "conversation"
    }
}
