package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Diff local settings/assistants into outbox mutations.
 */
class SettingsDomainSync(
    private val cloudSyncRepository: CloudSyncRepository,
    private val revisionDao: SyncEntityRevisionDAO,
    private val settingsStore: SettingsStore,
    appScope: AppScope,
) {
    init {
        appScope.launch {
            var previous: Settings? = null
            settingsStore.settingsFlow.collect { current ->
                onLocalSettingsChanged(previous, current)
                previous = current
            }
        }
    }

    suspend fun onLocalSettingsChanged(previous: Settings?, current: Settings) {
        if (cloudSyncRepository.isSuppressingLocalEnqueue) return
        if (previous == null || current.init || previous.init) return

        val prevMap = SyncableSettings.extract(previous)
        val currMap = SyncableSettings.extract(current)
        for (key in SyncableSettings.ALL_KEYS) {
            val oldVal = prevMap[key]
            val newVal = currMap[key]
            if (oldVal == newVal) continue
            enqueueSetting(key, newVal)
        }

        val prevAssistants = previous.assistants.associateBy { it.id.toString() }
        val currAssistants = current.assistants.associateBy { it.id.toString() }
        for ((id, assistant) in currAssistants) {
            val prev = prevAssistants[id]
            if (prev == assistant) continue
            enqueueAssistantUpsert(assistant)
        }
        for ((id, _) in prevAssistants) {
            if (id !in currAssistants) {
                enqueueAssistantDelete(id)
            }
        }
    }

    /**
     * When server bootstrap is empty, push current local settings/assistants once.
     */
    suspend fun seedLocalSnapshot(settings: Settings) {
        if (settings.init) return
        var enqueued = false
        val map = SyncableSettings.extract(settings)
        for ((key, value) in map) {
            val known = revisionDao.get(ENTITY_SETTING, key)
            if (known != null) continue
            if (enqueueSetting(key, value, requestSync = false)) enqueued = true
        }
        for (assistant in settings.assistants) {
            val id = assistant.id.toString()
            val known = revisionDao.get(ENTITY_ASSISTANT, id)
            if (known != null) continue
            if (enqueueAssistantUpsert(assistant, requestSync = false)) enqueued = true
        }
        if (enqueued) {
            Log.d(TAG, "seeded local snapshot into outbox")
        }
    }

    private suspend fun enqueueSetting(
        key: String,
        value: JsonElement?,
        requestSync: Boolean = true,
    ): Boolean {
        val rev = revisionDao.get(ENTITY_SETTING, key)?.revision ?: 0L
        val payload = buildJsonObject {
            put("value", value ?: JsonNull)
        }
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_SETTING,
            entityId = key,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        ) ?: return false
        Log.d(TAG, "enqueued setting $key rev=$rev mutation=$mutationId")
        if (requestSync) cloudSyncRepository.requestSync()
        return true
    }

    private suspend fun enqueueAssistantUpsert(
        assistant: Assistant,
        requestSync: Boolean = true,
    ): Boolean {
        val id = assistant.id.toString()
        val rev = revisionDao.get(ENTITY_ASSISTANT, id)?.revision ?: 0L
        val body = JsonInstant.encodeToJsonElement(Assistant.serializer(), assistant)
        val payload = assistantMutationPayload(body, assistant.name)
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_ASSISTANT,
            entityId = id,
            operation = "upsert",
            payloadJson = JsonInstant.encodeToString(payload),
            baseRevision = rev,
        ) ?: return false
        Log.d(TAG, "enqueued assistant $id rev=$rev mutation=$mutationId")
        if (requestSync) cloudSyncRepository.requestSync()
        return true
    }

    private suspend fun enqueueAssistantDelete(id: String): Boolean {
        val rev = revisionDao.get(ENTITY_ASSISTANT, id)?.revision ?: 0L
        val mutationId = cloudSyncRepository.enqueueMutation(
            entityType = ENTITY_ASSISTANT,
            entityId = id,
            operation = "delete",
            payloadJson = null,
            baseRevision = rev,
        ) ?: return false
        Log.d(TAG, "enqueued assistant delete $id rev=$rev mutation=$mutationId")
        cloudSyncRepository.requestSync()
        return true
    }

    suspend fun rememberRevision(entityType: String, entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = entityType,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "SettingsDomainSync"
        const val ENTITY_SETTING = "setting"
        const val ENTITY_ASSISTANT = "assistant"
    }
}
