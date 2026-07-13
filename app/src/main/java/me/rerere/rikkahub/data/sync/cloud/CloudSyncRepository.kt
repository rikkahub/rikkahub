package me.rerere.rikkahub.data.sync.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.SyncEntityRevisionDAO
import me.rerere.rikkahub.data.db.dao.SyncOutboxDAO
import me.rerere.rikkahub.data.db.dao.SyncStateDAO
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID

sealed class CloudSyncOutcome {
    data object Success : CloudSyncOutcome()
    data object Skipped : CloudSyncOutcome()
    data class Retryable(val message: String) : CloudSyncOutcome()
    data class Failed(val message: String) : CloudSyncOutcome()
}

data class ConnectionProbeResult(
    val status: ConnectionStatus,
    val message: String,
    val latencyMs: Long? = null,
    val serverInfo: ServerInfoResponse? = null,
)

/**
 * Local outbox + Perry connection/sync cycle.
 */
class CloudSyncRepository(
    private val appContext: Context,
    private val appScope: AppScope,
    private val outboxDao: SyncOutboxDAO,
    private val stateDao: SyncStateDAO,
    private val revisionDao: SyncEntityRevisionDAO,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) {
    // Set after Koin creates domain sync helpers to avoid ctor cycles.
    var settingsDomainSync: SettingsDomainSync? = null
    var conversationDomainSync: ConversationDomainSync? = null
    var folderDomainSync: FolderDomainSync? = null
    private val syncMutex = Mutex()
    private var pendingSyncJob: Job? = null
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.NOT_CONFIGURED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    @Volatile
    var isSuppressingLocalEnqueue: Boolean = false
        private set

    /**
     * Schedule a sync soon. AUTO mode runs in-process (debounced) so users do not
     * have to tap "Sync now"; WorkManager remains a process-death / retry backup.
     */
    fun requestSync() {
        pendingSyncJob?.cancel()
        pendingSyncJob = appScope.launch {
            // Coalesce rapid setting/conversation writes into one cycle.
            delay(SYNC_DEBOUNCE_MS)
            val outcome = runSyncCycle()
            when (outcome) {
                is CloudSyncOutcome.Retryable -> {
                    Log.w(TAG, "in-process sync retryable: ${outcome.message}")
                    CloudSyncWorker.enqueue(appContext)
                }
                is CloudSyncOutcome.Failed -> {
                    Log.w(TAG, "in-process sync failed: ${outcome.message}")
                }
                CloudSyncOutcome.Success -> Log.d(TAG, "in-process sync ok")
                CloudSyncOutcome.Skipped -> {
                    // Mode paused/local or already running — still arm WorkManager backup.
                    CloudSyncWorker.enqueue(appContext)
                }
            }
        }
    }

    suspend fun <T> withRemoteApply(block: suspend () -> T): T {
        isSuppressingLocalEnqueue = true
        return try {
            block()
        } finally {
            isSuppressingLocalEnqueue = false
        }
    }

    fun observeOutboxCount(): Flow<Int> = outboxDao.observeCount()

    fun observeSyncMode(): Flow<SyncMode> =
        stateDao.observe().map { SyncMode.fromStorage(it?.syncMode) }

    fun observeState(): Flow<SyncStateEntity?> = stateDao.observe()

    suspend fun ensureState(): SyncStateEntity {
        val existing = stateDao.get()
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val created = SyncStateEntity(id = 1, updatedAt = now)
        stateDao.upsert(created)
        return created
    }

    suspend fun setSyncMode(mode: SyncMode) {
        val current = ensureState()
        stateDao.upsert(
            current.copy(
                syncMode = mode.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
        when (mode) {
            SyncMode.LOCAL_ONLY -> _connectionStatus.value = ConnectionStatus.LOCAL_MODE
            SyncMode.PAUSED -> _connectionStatus.value = ConnectionStatus.PAUSED
            SyncMode.AUTO -> requestSync()
        }
    }

    suspend fun enqueueMutation(
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String?,
        baseRevision: Long,
        payloadSchemaVersion: Int = 1,
    ): String? {
        val state = ensureState()
        if (SyncMode.fromStorage(state.syncMode) == SyncMode.LOCAL_ONLY) {
            return null
        }
        val now = System.currentTimeMillis()
        val mutationId = UUID.randomUUID().toString()
        outboxDao.insert(
            SyncOutboxEntity(
                mutationId = mutationId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payloadJson,
                baseRevision = baseRevision,
                payloadSchemaVersion = payloadSchemaVersion,
                retryCount = 0,
                nextRetryAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
        return mutationId
    }

    suspend fun testConnection(): ConnectionProbeResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val config = settings.perryConfig
        val mode = SyncMode.fromStorage(ensureState().syncMode)
        // Explicit "Test connection" always probes the network; mode only annotates the result.
        if (!config.isConfigured()) {
            return@withContext publishProbe(
                ConnectionProbeResult(ConnectionStatus.NOT_CONFIGURED, "Server host is empty")
            )
        }

        _connectionStatus.value = ConnectionStatus.CHECKING
        val started = System.currentTimeMillis()
        val baseUrl = try {
            config.normalizedBaseUrl()
        } catch (e: Exception) {
            return@withContext publishProbe(
                ConnectionProbeResult(
                    ConnectionStatus.NOT_CONFIGURED,
                    "Invalid server URL: ${e.message}",
                )
            )
        }

        try {
            val anon = PerryApiClient(okHttpClient, baseUrl, deviceToken = null)
            try {
                anon.healthLive()
            } catch (e: Exception) {
                Log.w(TAG, "health/live failed for $baseUrl", e)
                return@withContext publishProbe(
                    ConnectionProbeResult(
                        status = ConnectionStatus.UNREACHABLE,
                        message = "health/live failed @ $baseUrl: ${describeError(e)}",
                        latencyMs = System.currentTimeMillis() - started,
                    )
                )
            }
            try {
                anon.healthReady()
            } catch (e: Exception) {
                Log.w(TAG, "health/ready failed for $baseUrl", e)
                return@withContext publishProbe(
                    ConnectionProbeResult(
                        status = ConnectionStatus.UNREACHABLE,
                        message = "health/ready failed @ $baseUrl: ${describeError(e)}",
                        latencyMs = System.currentTimeMillis() - started,
                    )
                )
            }

            val token = settings.perryDeviceToken
            if (token.isBlank()) {
                return@withContext publishProbe(
                    ConnectionProbeResult(
                        status = ConnectionStatus.AUTH_REQUIRED,
                        message = "Server reachable, but device token is empty. Register this device first.",
                        latencyMs = System.currentTimeMillis() - started,
                    )
                )
            }

            val authed = PerryApiClient(okHttpClient, baseUrl, deviceToken = token)
            val info = try {
                authed.serverInfo()
            } catch (e: PerryApiException) {
                Log.w(TAG, "server-info failed for $baseUrl", e)
                val status = when (e.httpStatus) {
                    401, 403 -> ConnectionStatus.AUTH_REQUIRED
                    else -> ConnectionStatus.UNREACHABLE
                }
                return@withContext publishProbe(
                    ConnectionProbeResult(
                        status = status,
                        message = "server-info failed @ $baseUrl: ${e.message ?: e.code} (HTTP ${e.httpStatus})",
                        latencyMs = System.currentTimeMillis() - started,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "server-info parse/network failed for $baseUrl", e)
                return@withContext publishProbe(
                    ConnectionProbeResult(
                        status = ConnectionStatus.UNREACHABLE,
                        message = "server-info failed @ $baseUrl: ${describeError(e)}",
                        latencyMs = System.currentTimeMillis() - started,
                    )
                )
            }

            val degraded = info.components.values.any {
                it.status == "degraded" || it.status == "error"
            }
            val status = when {
                mode == SyncMode.LOCAL_ONLY -> ConnectionStatus.LOCAL_MODE
                mode == SyncMode.PAUSED -> ConnectionStatus.PAUSED
                degraded -> ConnectionStatus.DEGRADED
                else -> ConnectionStatus.ONLINE
            }
            val modeNote = when (mode) {
                SyncMode.AUTO -> ""
                else -> " (mode=$mode, network OK)"
            }
            publishProbe(
                ConnectionProbeResult(
                    status = status,
                    message = "API ${info.apiVersion}$modeNote",
                    latencyMs = System.currentTimeMillis() - started,
                    serverInfo = info,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed for $baseUrl", e)
            publishProbe(
                ConnectionProbeResult(
                    ConnectionStatus.UNREACHABLE,
                    "probe failed @ $baseUrl: ${describeError(e)}",
                    latencyMs = System.currentTimeMillis() - started,
                )
            )
        }
    }

    private fun publishProbe(result: ConnectionProbeResult): ConnectionProbeResult {
        _connectionStatus.value = result.status
        _lastMessage.value = result.message
        return result
    }

    private fun describeError(e: Throwable): String {
        val base = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val cause = e.cause?.message?.takeIf { it.isNotBlank() }
        return if (cause != null && cause != base) "$base | cause=$cause" else base
    }

    suspend fun registerThisDevice(): Result<DeviceRegisterResponse> {
        return runCatching {
            val settings = settingsStore.settingsFlow.value
            val config = settings.perryConfig
            require(config.isConfigured()) { "Server host is empty" }
            require(config.bootstrapToken.isNotBlank()) { "Bootstrap token is empty" }
            val name = config.deviceName.ifBlank { android.os.Build.MODEL }
            val client = PerryApiClient(okHttpClient, config.normalizedBaseUrl())
            val registered = client.registerDevice(name, config.bootstrapToken)
            val now = System.currentTimeMillis()
            val state = ensureState()
            // Prefer AUTO after a successful registration so subsequent sync/tests are meaningful.
            stateDao.upsert(
                state.copy(
                    deviceId = registered.deviceId,
                    serverId = registered.userId,
                    syncMode = SyncMode.AUTO.name,
                    updatedAt = now,
                    lastError = null,
                )
            )
            settingsStore.update {
                it.copy(
                    perryDeviceToken = registered.deviceToken,
                    perryConfig = it.perryConfig.copy(
                        deviceName = name,
                        // Clear bootstrap token after successful registration
                        bootstrapToken = "",
                    ),
                )
            }
            registered
        }
    }

    suspend fun clearDeviceCredentials() {
        val state = ensureState()
        stateDao.upsert(
            state.copy(
                deviceId = null,
                serverId = null,
                changeCursor = 0,
                updatedAt = System.currentTimeMillis(),
                lastError = null,
            )
        )
        settingsStore.update { it.copy(perryDeviceToken = "") }
        _connectionStatus.value = ConnectionStatus.AUTH_REQUIRED
    }

    suspend fun resetCursor() {
        val state = ensureState()
        stateDao.upsert(state.copy(changeCursor = 0, updatedAt = System.currentTimeMillis()))
    }

    suspend fun runSyncCycle(): CloudSyncOutcome {
        // Avoid overlapping cycles (manual Sync now + WorkManager + settings enqueue).
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "sync cycle already running; skip")
            return CloudSyncOutcome.Skipped
        }
        return try {
            runSyncCycleLocked()
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun runSyncCycleLocked(): CloudSyncOutcome {
        val state = ensureState()
        val mode = SyncMode.fromStorage(state.syncMode)
        if (mode != SyncMode.AUTO) {
            return CloudSyncOutcome.Skipped
        }
        val settings = settingsStore.settingsFlow.value
        val config = settings.perryConfig
        val token = settings.perryDeviceToken
        if (!config.isConfigured() || token.isBlank() || state.deviceId.isNullOrBlank()) {
            return CloudSyncOutcome.Skipped
        }

        return try {
            withContext(Dispatchers.IO) {
                val client = PerryApiClient(
                    okHttpClient,
                    config.normalizedBaseUrl(),
                    deviceToken = token,
                )
                if (state.changeCursor <= 0L) {
                    val bootstrap = client.bootstrap()
                    applyBootstrap(bootstrap)
                    if (bootstrap.settings.isEmpty() && bootstrap.assistants.isEmpty()) {
                        settingsDomainSync?.seedLocalSnapshot(settingsStore.settingsFlow.value)
                    }
                    if (bootstrap.conversations.isEmpty()) {
                        conversationDomainSync?.seedLocalConversations()
                    }
                    if (bootstrap.conversationFolders.isEmpty()) {
                        folderDomainSync?.seedLocalFolders()
                    }
                }
                pushOutbox(client, state.deviceId!!)
                pullChanges(client)
                val now = System.currentTimeMillis()
                stateDao.updateCursor(
                    cursor = ensureState().changeCursor,
                    lastSuccessAt = now,
                    updatedAt = now,
                )
                _connectionStatus.value = ConnectionStatus.ONLINE
                CloudSyncOutcome.Success
            }
        } catch (e: PerryApiException) {
            Log.w(TAG, "sync failed: ${e.code} ${e.message}")
            stateDao.updateError(e.message, System.currentTimeMillis())
            when (e.httpStatus) {
                401, 403 -> {
                    _connectionStatus.value = ConnectionStatus.AUTH_REQUIRED
                    CloudSyncOutcome.Failed(e.message ?: e.code)
                }
                409 -> CloudSyncOutcome.Failed(e.message ?: "conflict")
                else -> CloudSyncOutcome.Retryable(e.message ?: e.code)
            }
        } catch (e: IOException) {
            // WSL/LAN blips are common; WorkManager will retry.
            Log.w(TAG, "sync network error: ${e.message}")
            stateDao.updateError(e.message, System.currentTimeMillis())
            CloudSyncOutcome.Retryable(e.message ?: "network error")
        } catch (e: Exception) {
            Log.e(TAG, "sync cycle failed", e)
            stateDao.updateError(e.message, System.currentTimeMillis())
            CloudSyncOutcome.Retryable(e.message ?: "sync error")
        }
    }

    private suspend fun pushOutbox(client: PerryApiClient, deviceId: String) {
        val batch = outboxDao.listReady(System.currentTimeMillis(), limit = 50)
        if (batch.isEmpty()) return
        val request = SyncMutationsRequest(
            deviceId = deviceId,
            mutations = batch.map { item ->
                SyncMutationItem(
                    mutationId = item.mutationId,
                    entityType = item.entityType,
                    entityId = item.entityId,
                    operation = item.operation,
                    baseRevision = item.baseRevision,
                    payloadSchemaVersion = item.payloadSchemaVersion,
                    payload = item.payloadJson?.let {
                        runCatching { JsonInstant.decodeFromString<JsonElement>(it) }.getOrNull()
                    },
                )
            },
        )
        val response = client.mutations(request)
        val now = System.currentTimeMillis()
        response.results.forEach { result ->
            when (result.status) {
                "applied", "already_applied" -> {
                    outboxDao.deleteById(result.mutationId)
                    result.revision?.let { rev ->
                        rememberRevision(result.entityType, result.entityId, rev)
                    }
                }
                "conflict" -> {
                    val existing = outboxDao.getById(result.mutationId)
                    result.revision?.let { rev ->
                        rememberRevision(result.entityType, result.entityId, rev)
                    }
                    // Server wins for v1: apply server payload and drop local mutation.
                    val operation = existing?.operation ?: "upsert"
                    result.serverPayload?.let { payload ->
                        applyEntityPayload(
                            result.entityType,
                            result.entityId,
                            operation,
                            payload,
                            result.revision,
                        )
                    }
                    outboxDao.deleteById(result.mutationId)
                    Log.w(TAG, "conflict on ${result.entityType}/${result.entityId}: ${result.message}")
                }
                "rejected" -> {
                    val existing = outboxDao.getById(result.mutationId) ?: return@forEach
                    outboxDao.update(
                        existing.copy(
                            retryCount = existing.retryCount + 1,
                            nextRetryAt = now + backoffMs(existing.retryCount + 1),
                            lastError = result.message ?: result.status,
                            updatedAt = now,
                        )
                    )
                }
            }
        }
        if (response.cursor > 0) {
            val state = ensureState()
            if (response.cursor > state.changeCursor) {
                stateDao.upsert(state.copy(changeCursor = response.cursor, updatedAt = now))
            }
        }
    }

    private suspend fun pullChanges(client: PerryApiClient) {
        var cursor = ensureState().changeCursor
        var guard = 0
        while (guard < 20) {
            guard++
            val page = client.changes(cursor = cursor, limit = 200)
            if (page.changes.isNotEmpty()) {
                applyChangeBatch(page.changes)
            }
            if (page.nextCursor > cursor) {
                cursor = page.nextCursor
                val state = ensureState()
                stateDao.upsert(
                    state.copy(
                        changeCursor = cursor,
                        lastSuccessAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        lastError = null,
                    )
                )
            }
            if (!page.hasMore || page.changes.isEmpty()) break
        }
    }

    private suspend fun applyBootstrap(bootstrap: SyncBootstrapResponse) {
        withRemoteApply {
            var settings = settingsStore.settingsFlow.value
            bootstrap.settings.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val key = obj["key"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val value = obj["value"] ?: return@forEach
                val revision = obj["revision"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                settings = SyncableSettings.applyKey(settings, key, value)
                rememberRevision(SettingsDomainSync.ENTITY_SETTING, key, revision)
            }
            val assistants = settings.assistants.toMutableList()
            bootstrap.assistants.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val payload = obj["payload"] ?: return@forEach
                val revision = obj["revision"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                val assistant = runCatching {
                    JsonInstant.decodeFromJsonElement(Assistant.serializer(), payload)
                }.getOrNull() ?: return@forEach
                val idx = assistants.indexOfFirst { it.id.toString() == id }
                if (idx >= 0) assistants[idx] = assistant else assistants.add(assistant)
                rememberRevision(SettingsDomainSync.ENTITY_ASSISTANT, id, revision)
            }
            settingsStore.update(settings.copy(assistants = assistants))
            // Folders first so UI membership resolves when conversation summaries land.
            bootstrap.conversationFolders.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val revision = obj["revision"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                folderDomainSync?.applyRemotePayload(
                    entityId = id,
                    operation = "upsert",
                    payload = obj,
                    revision = revision,
                )
            }
            bootstrap.conversations.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val revision = obj["revision"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                conversationDomainSync?.applyRemotePayload(
                    entityId = id,
                    operation = "upsert",
                    payload = obj,
                    revision = revision,
                )
            }
            val state = ensureState()
            stateDao.upsert(
                state.copy(
                    changeCursor = bootstrap.cursor,
                    lastSuccessAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    lastError = null,
                )
            )
        }
    }

    private suspend fun applyChangeBatch(changes: List<SyncChangeItem>) {
        withRemoteApply {
            var settings = settingsStore.settingsFlow.value
            var assistants = settings.assistants.toMutableList()
            var dirty = false
            // Stable order: settings/assistants, then folders, then conversations.
            val ordered = changes.sortedBy { change ->
                when (change.entityType) {
                    SettingsDomainSync.ENTITY_SETTING -> 0
                    SettingsDomainSync.ENTITY_ASSISTANT -> 1
                    FolderDomainSync.ENTITY_FOLDER -> 2
                    ConversationDomainSync.ENTITY_CONVERSATION -> 3
                    else -> 9
                }
            }
            ordered.forEach { change ->
                when (change.entityType) {
                    SettingsDomainSync.ENTITY_SETTING -> {
                        val key = change.entityId
                        if (change.operation == "delete") {
                            rememberRevision(change.entityType, key, change.revision)
                            return@forEach
                        }
                        val value = change.payload
                            ?.let { it as? JsonObject }
                            ?.get("value")
                            ?: return@forEach
                        settings = SyncableSettings.applyKey(settings, key, value)
                        rememberRevision(change.entityType, key, change.revision)
                        dirty = true
                    }
                    SettingsDomainSync.ENTITY_ASSISTANT -> {
                        val id = change.entityId
                        if (change.operation == "delete") {
                            assistants.removeAll { it.id.toString() == id }
                            rememberRevision(change.entityType, id, change.revision)
                            dirty = true
                            return@forEach
                        }
                        val payload = change.payload
                            ?.let { it as? JsonObject }
                            ?.get("payload")
                            ?: return@forEach
                        val assistant = runCatching {
                            JsonInstant.decodeFromJsonElement(Assistant.serializer(), payload)
                        }.getOrNull() ?: return@forEach
                        val idx = assistants.indexOfFirst { it.id.toString() == id }
                        if (idx >= 0) assistants[idx] = assistant else assistants.add(assistant)
                        rememberRevision(change.entityType, id, change.revision)
                        dirty = true
                    }
                    FolderDomainSync.ENTITY_FOLDER -> {
                        folderDomainSync?.applyRemotePayload(
                            entityId = change.entityId,
                            operation = change.operation,
                            payload = change.payload,
                            revision = change.revision,
                        )
                        rememberRevision(change.entityType, change.entityId, change.revision)
                    }
                    ConversationDomainSync.ENTITY_CONVERSATION -> {
                        conversationDomainSync?.applyRemotePayload(
                            entityId = change.entityId,
                            operation = change.operation,
                            payload = change.payload,
                            revision = change.revision,
                        )
                        rememberRevision(change.entityType, change.entityId, change.revision)
                    }
                }
            }
            if (dirty) {
                settingsStore.update(settings.copy(assistants = assistants))
            }
        }
    }

    private suspend fun applyEntityPayload(
        entityType: String,
        entityId: String,
        operation: String,
        payload: JsonElement,
        revision: Long?,
    ) {
        withRemoteApply {
            var settings = settingsStore.settingsFlow.value
            when (entityType) {
                SettingsDomainSync.ENTITY_SETTING -> {
                    if (operation != "delete") {
                        val value = (payload as? JsonObject)?.get("value") ?: return@withRemoteApply
                        settings = SyncableSettings.applyKey(settings, entityId, value)
                        settingsStore.update(settings)
                    }
                }
                SettingsDomainSync.ENTITY_ASSISTANT -> {
                    val list = settings.assistants.toMutableList()
                    if (operation == "delete") {
                        list.removeAll { it.id.toString() == entityId }
                    } else {
                        val body = (payload as? JsonObject)?.get("payload") ?: return@withRemoteApply
                        val assistant = runCatching {
                            JsonInstant.decodeFromJsonElement(Assistant.serializer(), body)
                        }.getOrNull() ?: return@withRemoteApply
                        val idx = list.indexOfFirst { it.id.toString() == entityId }
                        if (idx >= 0) list[idx] = assistant else list.add(assistant)
                    }
                    settingsStore.update(settings.copy(assistants = list))
                }
                ConversationDomainSync.ENTITY_CONVERSATION -> {
                    conversationDomainSync?.applyRemotePayload(
                        entityId = entityId,
                        operation = operation,
                        payload = payload,
                        revision = revision ?: 0L,
                    )
                }
                FolderDomainSync.ENTITY_FOLDER -> {
                    folderDomainSync?.applyRemotePayload(
                        entityId = entityId,
                        operation = operation,
                        payload = payload,
                        revision = revision ?: 0L,
                    )
                }
            }
            revision?.let { rememberRevision(entityType, entityId, it) }
        }
    }

    private suspend fun rememberRevision(entityType: String, entityId: String, revision: Long) {
        revisionDao.upsert(
            SyncEntityRevisionEntity(
                entityType = entityType,
                entityId = entityId,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun backoffMs(retryCount: Int): Long {
        val steps = longArrayOf(5_000, 15_000, 60_000, 300_000, 1_800_000)
        val idx = (retryCount - 1).coerceIn(0, steps.lastIndex)
        val base = steps[idx]
        val jitter = (base * 0.2).toLong()
        return base + (-jitter..jitter).random()
    }

    companion object {
        private const val TAG = "CloudSyncRepository"
        private const val SYNC_DEBOUNCE_MS = 800L
    }
}
