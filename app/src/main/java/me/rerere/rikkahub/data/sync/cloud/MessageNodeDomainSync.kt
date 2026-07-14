package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Message node domain for Phase 6.
 * Whole node (including branch candidates) is the conflict atom.
 */
class MessageNodeDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val conversationDAO: ConversationDAO,
) {
    suspend fun enqueueUpsert(
        conversationId: String,
        nodeIndex: Int,
        node: MessageNode,
        syncEnabled: Boolean,
    ) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        if (!syncEnabled) return
        val entityId = node.id.toString()
        val rev = revisionDao.get(ENTITY_MESSAGE_NODE, entityId)?.revision ?: 0L
        val payload = buildMutationPayload(conversationId, nodeIndex, node)
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_MESSAGE_NODE,
            entityId = entityId,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued message_node upsert $entityId idx=$nodeIndex rev=$rev")
        }
    }

    suspend fun enqueueDelete(nodeId: String, baseRevision: Long? = null) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        val rev = baseRevision
            ?: revisionDao.get(ENTITY_MESSAGE_NODE, nodeId)?.revision
            ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_MESSAGE_NODE,
            entityId = nodeId,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        )
        if (mutationId != null) {
            Log.d(TAG, "enqueued message_node delete $nodeId rev=$rev")
        }
    }

    /**
     * Enqueue all nodes of a conversation after a stable local save.
     * Caller should requestSync once after this returns.
     */
    suspend fun enqueueConversationNodes(
        conversationId: String,
        nodes: List<MessageNode>,
        syncEnabled: Boolean,
    ) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        if (!syncEnabled) return
        nodes.forEachIndexed { index, node ->
            enqueueUpsert(conversationId, index, node, syncEnabled = true)
        }
        if (nodes.isNotEmpty()) {
            cloudSyncRepository.requestSync()
        }
    }

    /**
     * Bootstrap sets change_cursor to head but only ships conversation summaries.
     * Empty local threads must hydrate bodies via /v1/conversations/{id}/nodes.
     */
    suspend fun hydrateMissingNodes(client: PerryApiClient) {
        val ids = conversationDAO.getAllIds()
        var hydratedConversations = 0
        var hydratedNodes = 0
        for (conversationId in ids) {
            val parent = conversationDAO.getConversationById(conversationId) ?: continue
            if (!parent.syncEnabled || parent.deletedAt != null) continue
            if (messageNodeDAO.countByConversation(conversationId) > 0) continue
            var beforeIndex: Int? = null
            var pages = 0
            var gotAny = false
            while (pages < 40) {
                pages++
                val page = client.listMessageNodes(
                    conversationId = conversationId,
                    beforeIndex = beforeIndex,
                    limit = 200,
                )
                if (page.items.isEmpty()) break
                gotAny = true
                for (item in page.items) {
                    if (item.deletedAt != null) continue
                    val payload = buildJsonObject {
                        put("conversation_id", JsonPrimitive(item.conversationId))
                        put("node_index", JsonPrimitive(item.nodeIndex))
                        put("select_index", JsonPrimitive(item.selectIndex))
                        put("messages", item.messages ?: JsonArray(emptyList()))
                    }
                    applyRemotePayload(
                        entityId = item.id,
                        operation = "upsert",
                        payload = payload,
                        revision = item.revision,
                    )
                    hydratedNodes++
                }
                if (!page.hasMore) break
                beforeIndex = page.oldestIndex ?: page.items.minOfOrNull { it.nodeIndex } ?: break
            }
            if (gotAny) hydratedConversations++
        }
        if (hydratedConversations > 0) {
            Log.i(
                TAG,
                "hydrated nodes conversations=$hydratedConversations nodes=$hydratedNodes",
            )
        }
    }

    suspend fun applyRemotePayload(
        entityId: String,
        operation: String,
        payload: JsonElement?,
        revision: Long,
    ) {
        if (operation == "delete") {
            messageNodeDAO.deleteById(entityId)
            rememberRevision(entityId, revision)
            return
        }
        val root = payload as? JsonObject ?: return
        val body = (root["payload"] as? JsonObject) ?: root
        val entity = payloadToEntity(entityId, root, body) ?: return
        // Parent conversation must exist locally (summary may land first).
        val parent = conversationDAO.getConversationById(entity.conversationId)
        if (parent == null) {
            Log.w(TAG, "skip node $entityId: conversation ${entity.conversationId} missing")
            return
        }
        if (!parent.syncEnabled) {
            Log.d(TAG, "skip node $entityId: conversation local-only")
            return
        }
        // Server wins for this index: drop other local rows claiming the same slot.
        messageNodeDAO.deleteOthersAtIndex(
            conversationId = entity.conversationId,
            nodeIndex = entity.nodeIndex,
            keepId = entityId,
        )
        messageNodeDAO.insert(entity)
        rememberRevision(entityId, revision)
        Log.d(TAG, "applied remote message_node $entityId idx=${entity.nodeIndex}")
    }

    private fun payloadToEntity(
        entityId: String,
        root: JsonObject,
        body: JsonObject,
    ): MessageNodeEntity? {
        fun prim(vararg keys: String): JsonPrimitive? {
            for (k in keys) {
                val v = root[k] ?: body[k]
                val p = v as? JsonPrimitive ?: continue
                if (v is JsonNull) continue
                return p
            }
            return null
        }

        fun str(vararg keys: String): String? = prim(*keys)?.contentOrNull

        fun int(vararg keys: String, default: Int = 0): Int {
            val p = prim(*keys) ?: return default
            p.intOrNull?.let { return it }
            return p.contentOrNull?.toIntOrNull() ?: default
        }

        val conversationId = str("conversation_id", "conversationId") ?: return null
        val nodeIndex = int("node_index", "nodeIndex")
        val selectIndex = int("select_index", "selectIndex")
        val messagesEl = root["messages"] ?: body["messages"]
        val messagesJson = when (messagesEl) {
            is JsonArray -> JsonInstant.encodeToString(messagesEl)
            is JsonPrimitive -> messagesEl.contentOrNull ?: "[]"
            null, is JsonNull -> "[]"
            else -> "[]"
        }
        return MessageNodeEntity(
            id = entityId,
            conversationId = conversationId,
            nodeIndex = nodeIndex,
            messages = messagesJson,
            selectIndex = selectIndex,
        )
    }

    private fun buildMutationPayload(
        conversationId: String,
        nodeIndex: Int,
        node: MessageNode,
    ): JsonObject {
        val messagesJson = JsonInstant.encodeToString(node.messages)
        val messagesEl = runCatching {
            JsonInstant.parseToJsonElement(messagesJson)
        }.getOrDefault(JsonArray(emptyList()))
        val body = buildJsonObject {
            put("conversation_id", JsonPrimitive(conversationId))
            put("node_index", JsonPrimitive(nodeIndex))
            put("select_index", JsonPrimitive(node.selectIndex))
            put("messages", messagesEl)
        }
        return buildJsonObject {
            put("payload", body)
        }
    }

    private suspend fun rememberRevision(entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = ENTITY_MESSAGE_NODE,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "MessageNodeDomainSync"
        const val ENTITY_MESSAGE_NODE = "message_node"
    }
}
