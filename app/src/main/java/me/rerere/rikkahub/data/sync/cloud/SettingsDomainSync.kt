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
            // Compare canonical JSON strings: JsonElement structural equality can
            // miss equivalent provider lists depending on element implementation.
            if (jsonEquals(oldVal, newVal)) continue
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
     * Push any local setting keys / assistants that have no known server revision yet.
     * Safe to call repeatedly: keys that already have a revision are skipped.
     * This covers upgrades that add new sync keys (e.g. providers) after older
     * settings already exist on the server.
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
            Log.d(TAG, "seeded local snapshot into outbox (missing-revision keys)")
        }
    }

    /**
     * Force-enqueue full current snapshot for settings keys + all assistants.
     * Used after ZIP import so content changes still push even when a server
     * revision already exists (seedLocalSnapshot would skip those keys).
     */
    suspend fun forceEnqueueSnapshot(
        settings: Settings,
        keys: Set<String> = SyncableSettings.ALL_KEYS,
        assistants: List<Assistant> = settings.assistants,
        requestSync: Boolean = false,
    ) {
        if (settings.init) return
        var enqueued = false
        val map = SyncableSettings.extract(settings)
        for (key in keys) {
            val value = map[key] ?: continue
            if (enqueueSetting(key, value, requestSync = false)) enqueued = true
        }
        for (assistant in assistants) {
            if (enqueueAssistantUpsert(assistant, requestSync = false)) enqueued = true
        }
        if (enqueued) {
            Log.i(TAG, "force-enqueued snapshot keys=${keys.size} assistants=${assistants.size}")
            if (requestSync) cloudSyncRepository.requestSync()
        }
    }

    /**
     * Diff two settings snapshots and enqueue only changed keys / assistants.
     * Prefer this after import when [previous] is known.
     */
    suspend fun enqueueDiff(previous: Settings?, current: Settings, requestSync: Boolean = false) {
        if (current.init) return
        if (previous == null || previous.init) {
            forceEnqueueSnapshot(current, requestSync = requestSync)
            return
        }
        // Temporarily allow enqueue even if suppress flag is set by callers that
        // already exited withRemoteApply — this is an explicit local push.
        onLocalSettingsChanged(previous, current)
        if (requestSync) cloudSyncRepository.requestSync()
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

    private fun jsonEquals(a: JsonElement?, b: JsonElement?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a == b) return true
        return runCatching {
            JsonInstant.encodeToString(JsonElement.serializer(), a) ==
                JsonInstant.encodeToString(JsonElement.serializer(), b)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "SettingsDomainSync"
        const val ENTITY_SETTING = "setting"
        const val ENTITY_ASSISTANT = "assistant"
    }
}
