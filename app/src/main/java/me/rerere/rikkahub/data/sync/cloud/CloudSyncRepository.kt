package me.rerere.rikkahub.data.sync.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import me.rerere.ai.provider.ProviderSetting
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
    var messageNodeDomainSync: MessageNodeDomainSync? = null
    var memoryDomainSync: MemoryDomainSync? = null
    var favoriteDomainSync: FavoriteDomainSync? = null
    var fileDomainSync: FileDomainSync? = null
    private val syncMutex = Mutex()
    private var pendingSyncJob: Job? = null
    @Volatile
    private var syncRescheduleRequested = false
    @Volatile
    private var bulkEnqueueMode = false
    @Volatile
    private var bulkSyncRequested = false
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
    fun requestFileTransfer() {
        FileTransferWorker.enqueue(appContext)
    }

    fun createAuthenticatedClient(): PerryApiClient? {
        val settings = settingsStore.settingsFlow.value
        val config = settings.perryConfig
        val token = settings.perryDeviceToken
        if (!config.isConfigured() || token.isBlank()) return null
        return PerryApiClient(
            okHttpClient,
            config.normalizedBaseUrl(),
            deviceToken = token,
        )
    }

    /**
     * Coalesce sync requests without cancelling an in-flight cycle.
     * Cancelling mid-push left assistants/settings stranded behind conversation floods.
     */
    fun requestSync() {
        if (bulkEnqueueMode) {
            bulkSyncRequested = true
            return
        }
        syncRescheduleRequested = true
        val existing = pendingSyncJob
        if (existing != null && existing.isActive) return
        pendingSyncJob = appScope.launch {
            try {
                do {
                    syncRescheduleRequested = false
                    delay(SYNC_DEBOUNCE_MS)
                    // Another request during debounce: re-wait once more.
                    if (syncRescheduleRequested) continue
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
                            CloudSyncWorker.enqueue(appContext)
                        }
                    }
                } while (syncRescheduleRequested)
            } finally {
                pendingSyncJob = null
                if (syncRescheduleRequested) {
                    requestSync()
                }
            }
        }
    }

    /**
     * Bulk import mode: still enqueue outbox rows, but defer requestSync until end.
     */
    suspend fun <T> withBulkEnqueue(block: suspend () -> T): T {
        bulkEnqueueMode = true
        bulkSyncRequested = false
        return try {
            block()
        } finally {
            bulkEnqueueMode = false
            if (bulkSyncRequested) {
                requestSync()
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
        // Coalesce: keep only the latest pending mutation per entity so rapid
        // toggles do not push stale intermediate snapshots / cause conflicts.
        outboxDao.deleteByEntity(entityType, entityId)
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

    suspend fun hasPendingOutbox(entityType: String, entityId: String): Boolean {
        return outboxDao.countByEntity(entityType, entityId) > 0
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
            // Never read Settings.dummy() from first page composition.
            val settings = settingsStore.awaitReady()
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
                val refreshed = PerryCatalog.refreshPerryCredentials(
                    providers = it.providers,
                    resolveBaseUrl = { monelId ->
                        PerryApiClient(okHttpClient, it.perryConfig.normalizedBaseUrl())
                            .aiProviderBaseUrl(monelId)
                    },
                    deviceToken = registered.deviceToken,
                )
                it.copy(
                    perryDeviceToken = registered.deviceToken,
                    perryConfig = it.perryConfig.copy(
                        deviceName = name,
                        // Clear bootstrap token after successful registration
                        bootstrapToken = "",
                    ),
                    providers = refreshed,
                )
            }
            registered
        }
    }

    /**
     * Materialize one OpenAI-compatible provider shell per Monel provider.
     * Models stay empty until the user adds them on the Models tab (chat switcher only shows added models).
     */
    suspend fun importMonelProviders(): Result<Int> {
        return runCatching {
            withContext(Dispatchers.IO) {
                val settings = settingsStore.settingsFlow.value
                val client = createAuthenticatedClient()
                    ?: error("Perry not configured or device token missing")
                val catalog = client.catalogProviders()
                require(catalog.isNotEmpty()) { "Monel catalog returned no providers" }
                val token = settings.perryDeviceToken
                val existingOpenAi = settings.providers.filterIsInstance<ProviderSetting.OpenAI>()
                    .associateBy { it.id }
                val imported = catalog.map { dto ->
                    val id = PerryCatalog.providerUuid(dto.id)
                    PerryCatalog.toOpenAIProvider(
                        dto = dto,
                        perryBaseUrlForProvider = client.aiProviderBaseUrl(dto.id),
                        deviceToken = token,
                        existing = existingOpenAi[id],
                    )
                }
                val merged = PerryCatalog.mergeProviders(settings.providers, imported)
                settingsStore.update { it.copy(providers = merged) }
                imported.size
            }
        }
    }

    /** Browse remote Monel models for a Perry-backed provider (does not auto-add to chat). */
    suspend fun listMonelCatalogModels(provider: ProviderSetting): List<me.rerere.ai.provider.Model> {
        return withContext(Dispatchers.IO) {
            if (!PerryCatalog.isPerryGateway(provider)) return@withContext emptyList()
            val monelId = when (provider) {
                is ProviderSetting.OpenAI -> PerryCatalog.monelProviderIdFromBaseUrl(provider.baseUrl)
                else -> null
            } ?: return@withContext emptyList()
            val client = createAuthenticatedClient()
                ?: error("Perry not configured or device token missing")
            // Keep credentials in sync with current Perry host/token before browsing.
            refreshPerryProviderCredentialsInPlace()
            runCatching {
                val grouped = client.catalogModelsForProvider(monelId)
                PerryCatalog.toBrowseModels(
                    monelProviderId = monelId,
                    entries = grouped.modelEntries,
                    fallbackIds = grouped.models,
                )
            }.getOrElse {
                val flat = client.catalogModels()
                PerryCatalog.toBrowseModelsFromFlat(monelId, flat)
            }
        }
    }

    suspend fun refreshPerryProviderCredentialsInPlace() {
        val settings = settingsStore.settingsFlow.value
        val token = settings.perryDeviceToken
        if (!settings.perryConfig.isConfigured() || token.isBlank()) return
        val client = PerryApiClient(
            okHttpClient,
            settings.perryConfig.normalizedBaseUrl(),
            deviceToken = token,
        )
        val refreshed = PerryCatalog.refreshPerryCredentials(
            providers = settings.providers,
            resolveBaseUrl = { monelId -> client.aiProviderBaseUrl(monelId) },
            deviceToken = token,
        )
        if (refreshed != settings.providers) {
            settingsStore.update { it.copy(providers = refreshed) }
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
                    // Seed any local keys that have no revision yet (e.g. newly
                    // added "providers" after older settings already exist on server).
                    // Previously this only ran when bootstrap was completely empty,
                    // so providers never left device A.
                    settingsDomainSync?.seedLocalSnapshot(settingsStore.settingsFlow.value)
                    if (bootstrap.conversations.isEmpty()) {
                        conversationDomainSync?.seedLocalConversations()
                    }
                    if (bootstrap.conversationFolders.isEmpty()) {
                        folderDomainSync?.seedLocalFolders()
                    }
                    if (bootstrap.assistantMemories.isEmpty()) {
                        memoryDomainSync?.seedLocalMemories()
                    }
                    if (bootstrap.favorites.isEmpty()) {
                        favoriteDomainSync?.seedLocalFavorites()
                    }
                    if (bootstrap.files.isEmpty()) {
                        fileDomainSync?.seedLocalFiles()
                    }
                } else {
                    // Upgrade path: device already past bootstrap but missing
                    // revisions for new sync keys (providers). Push once.
                    settingsDomainSync?.seedLocalSnapshot(settingsStore.settingsFlow.value)
                }
                // Push local outbox first so A exports land before B reconciles.
                pushOutbox(client, state.deviceId!!)
                // Devices that already advanced change_cursor never re-run full bootstrap,
                // so settings/assistants missed due to old conflict bugs stay missing forever.
                // Reconcile those small domains from bootstrap every cycle (not conversations).
                runCatching {
                    val snap = client.bootstrap()
                    reconcileSettingsAndAssistants(snap)
                    Log.i(
                        TAG,
                        "reconcile bootstrap: settings=${snap.settings.size} " +
                            "assistants=${snap.assistants.size} cursor=${snap.cursor}",
                    )
                }.onFailure {
                    Log.w(TAG, "settings/assistants reconcile failed: ${it.message}")
                }
                // If local still has entities with no/outdated server revision, push again
                // after reconcile (covers ZIP import on A when providers already had rev).
                settingsDomainSync?.seedLocalSnapshot(settingsStore.settingsFlow.value)
                // Second push for anything enqueued during seed after first push.
                pushOutbox(client, state.deviceId!!)
                pullChanges(client)
                // Bootstrap/changes do not embed message_node bodies for all history;
                // fill empty local conversations from /conversations/{id}/nodes.
                runCatching {
                    messageNodeDomainSync?.hydrateMissingNodes(client)
                }.onFailure {
                    Log.w(TAG, "message node hydrate failed: ${it.message}")
                }
                // Only push local pending uploads / explicit on-demand downloads.
                // Do not bulk-download every remote file after each sync.
                requestFileTransfer()
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
        } catch (e: CancellationException) {
            throw e
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
        // Drain in loops: settings/assistants may enqueue mid-cycle after seed.
        // High ceiling for large ZIP imports (hundreds of conversation/node rows).
        var guard = 0
        while (guard < 80) {
            guard++
            val batch = outboxDao.listReady(System.currentTimeMillis(), limit = 50)
            if (batch.isEmpty()) {
                if (guard == 1) Log.d(TAG, "pushOutbox: empty")
                return
            }
            Log.i(
                TAG,
                "pushOutbox: ${batch.size} mutations " +
                    batch.groupingBy { it.entityType }.eachCount(),
            )
            // Parents first so message_node mutations find conversation on server.
            val orderedBatch = batch.sortedWith(
                compareBy<SyncOutboxEntity> { outboxPushPriority(it.entityType) }
                    .thenBy { it.createdAt },
            )
            val request = SyncMutationsRequest(
                deviceId = deviceId,
                mutations = orderedBatch.map { item ->
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
            var applied = 0
            var conflicts = 0
            var rejected = 0
            response.results.forEach { result ->
            when (result.status) {
                "applied", "already_applied" -> {
                    applied++
                    outboxDao.deleteById(result.mutationId)
                    result.revision?.let { rev ->
                        rememberRevision(result.entityType, result.entityId, rev)
                    }
                }
                "conflict" -> {
                    conflicts++
                    val existing = outboxDao.getById(result.mutationId)
                    val msg = result.message.orEmpty()
                    // After wipe/reimport, local revision table may still claim rev>0 while
                    // server row is gone → "base_revision must be 0". Requeue as create.
                    val createRetry = existing != null &&
                        existing.operation != "delete" &&
                        (
                            msg.contains("base_revision must be 0") ||
                                (msg.contains("entity does not exist") && (result.revision ?: 0L) == 0L)
                            )
                    if (createRetry) {
                        outboxDao.update(
                            existing!!.copy(
                                baseRevision = 0L,
                                retryCount = existing.retryCount + 1,
                                nextRetryAt = now,
                                lastError = msg,
                                updatedAt = now,
                            ),
                        )
                        Log.w(
                            TAG,
                            "conflict requeue base0 ${result.entityType}/${result.entityId}: $msg",
                        )
                    } else {
                        result.revision?.let { rev ->
                            rememberRevision(result.entityType, result.entityId, rev)
                        }
                        // Drop local outbox FIRST so applyEntityPayload is not skipped by
                        // hasPendingOutbox (assistants previously froze on shared default UUIDs).
                        outboxDao.deleteById(result.mutationId)
                        val operation = existing?.operation ?: "upsert"
                        result.serverPayload?.let { payload ->
                            applyEntityPayload(
                                result.entityType,
                                result.entityId,
                                operation,
                                payload,
                                result.revision,
                                forceApply = true,
                            )
                        }
                        Log.w(
                            TAG,
                            "conflict on ${result.entityType}/${result.entityId}: $msg",
                        )
                    }
                }
                "rejected" -> {
                    rejected++
                    val existing = outboxDao.getById(result.mutationId) ?: return@forEach
                    outboxDao.update(
                        existing.copy(
                            retryCount = existing.retryCount + 1,
                            nextRetryAt = now + backoffMs(existing.retryCount + 1),
                            lastError = result.message ?: result.status,
                            updatedAt = now,
                        )
                    )
                    Log.w(
                        TAG,
                        "rejected ${result.entityType}/${result.entityId}: ${result.message}",
                    )
                }
                else -> {
                    Log.w(TAG, "unknown mutation status ${result.status} for ${result.entityType}/${result.entityId}")
                }
            }
            }
            Log.i(TAG, "pushOutbox result applied=$applied conflicts=$conflicts rejected=$rejected")
            if (response.cursor > 0) {
                val st = ensureState()
                if (response.cursor > st.changeCursor) {
                    stateDao.upsert(st.copy(changeCursor = response.cursor, updatedAt = now))
                }
            }
            // Continue loop if more ready items appeared (or batch was full).
            if (batch.size < 50 && applied == 0 && conflicts == 0) break
        }
    }

    private suspend fun pullChanges(client: PerryApiClient) {
        var cursor = ensureState().changeCursor
        var guard = 0
        // Large ZIP imports produce thousands of change_log rows; 20*200 was too small.
        while (guard < 200) {
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

    /**
     * Apply settings + assistants from bootstrap for devices that already have a
     * change cursor (incremental-only path). Uses remote revision >= local known
     * revision, and always inserts missing assistant ids. Does not wipe local-only
     * assistants that were never pushed (they stay until next local seed push).
     */
    private suspend fun reconcileSettingsAndAssistants(bootstrap: SyncBootstrapResponse) {
        withRemoteApply {
            var settings = settingsStore.settingsFlow.value
            var dirty = false
            bootstrap.settings.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val key = obj["key"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val value = obj["value"] ?: return@forEach
                val revision = obj["revision"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                val known = revisionDao.get(SettingsDomainSync.ENTITY_SETTING, key)?.revision ?: 0L
                // Always apply when remote is newer OR local never saw this key revision.
                // For providers/display_setting also re-apply equal revision if local list
                // is empty/default (repair after failed earlier apply).
                val shouldApply = revision > known ||
                    (revision >= known && key == SyncableSettings.PROVIDERS && settings.providers.isEmpty()) ||
                    (revision >= known && key == SyncableSettings.DISPLAY_SETTING &&
                        settings.displaySetting.userNickname.isBlank())
                if (!shouldApply && revision == known) {
                    // Still refresh revision stamp; skip body when already current.
                    return@forEach
                }
                if (revision < known) return@forEach
                if (hasPendingOutbox(SettingsDomainSync.ENTITY_SETTING, key)) {
                    Log.d(TAG, "reconcile skip setting $key; local outbox pending")
                    return@forEach
                }
                settings = SyncableSettings.applyKey(settings, key, value)
                rememberRevision(SettingsDomainSync.ENTITY_SETTING, key, revision)
                dirty = true
                Log.i(TAG, "reconcile applied setting key=$key rev=$revision (was $known)")
            }
            val assistants = settings.assistants.toMutableList()
            bootstrap.assistants.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val payload = obj["payload"] ?: return@forEach
                val revision = obj["revision"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                if (hasPendingOutbox(SettingsDomainSync.ENTITY_ASSISTANT, id)) {
                    Log.d(TAG, "reconcile skip assistant $id; local outbox pending")
                    return@forEach
                }
                val known = revisionDao.get(SettingsDomainSync.ENTITY_ASSISTANT, id)?.revision ?: 0L
                val idx = assistants.indexOfFirst { it.id.toString() == id }
                val missingLocally = idx < 0
                if (!missingLocally && revision < known) return@forEach
                if (!missingLocally && revision == known) {
                    // Same rev: still repair empty-name default shells from remote if remote has name.
                    val remoteName = obj["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val localName = assistants[idx].name
                    if (localName.isNotBlank() || remoteName.isBlank()) return@forEach
                }
                val assistant = runCatching {
                    JsonInstant.decodeFromJsonElement(Assistant.serializer(), payload)
                }.getOrNull() ?: return@forEach
                if (idx >= 0) assistants[idx] = assistant else assistants.add(assistant)
                rememberRevision(SettingsDomainSync.ENTITY_ASSISTANT, id, revision)
                dirty = true
                Log.i(
                    TAG,
                    "reconcile applied assistant id=$id name=${assistant.name} rev=$revision " +
                        "missingLocal=$missingLocally",
                )
            }
            if (dirty) {
                settingsStore.update(settings.copy(assistants = assistants))
            }
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
            // File metadata first so attachments can resolve after assistants/messages land.
            bootstrap.files.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val revision = obj["revision"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                fileDomainSync?.applyRemotePayload(
                    entityId = id,
                    operation = "upsert",
                    payload = obj,
                    revision = revision,
                )
            }
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
            bootstrap.assistantMemories.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val revision = obj["revision"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                memoryDomainSync?.applyRemotePayload(
                    entityId = id,
                    operation = "upsert",
                    payload = obj,
                    revision = revision,
                )
            }
            bootstrap.favorites.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                val revision = obj["revision"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
                favoriteDomainSync?.applyRemotePayload(
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
            // Stable order: settings/assistants, folders, conversations, then nodes.
            val ordered = changes.sortedBy { change ->
                when (change.entityType) {
                    SettingsDomainSync.ENTITY_SETTING -> 0
                    FileDomainSync.ENTITY_FILE -> 1
                    SettingsDomainSync.ENTITY_ASSISTANT -> 2
                    MemoryDomainSync.ENTITY_MEMORY -> 3
                    FolderDomainSync.ENTITY_FOLDER -> 4
                    ConversationDomainSync.ENTITY_CONVERSATION -> 5
                    MessageNodeDomainSync.ENTITY_MESSAGE_NODE -> 6
                    FavoriteDomainSync.ENTITY_FAVORITE -> 7
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
                        // Local edits still in outbox win over remote echo/stale pull.
                        // Still advance revision so we do not re-pull forever after push.
                        if (hasPendingOutbox(SettingsDomainSync.ENTITY_ASSISTANT, id)) {
                            Log.d(TAG, "skip remote assistant $id; local outbox pending")
                            rememberRevision(change.entityType, id, change.revision)
                            return@forEach
                        }
                        if (change.operation == "delete") {
                            assistants.removeAll { it.id.toString() == id }
                            rememberRevision(change.entityType, id, change.revision)
                            dirty = true
                            return@forEach
                        }
                        val payload = change.payload
                            ?.let { it as? JsonObject }
                            ?.get("payload")
                            ?: change.payload
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
                    MessageNodeDomainSync.ENTITY_MESSAGE_NODE -> {
                        messageNodeDomainSync?.applyRemotePayload(
                            entityId = change.entityId,
                            operation = change.operation,
                            payload = change.payload,
                            revision = change.revision,
                        )
                        rememberRevision(change.entityType, change.entityId, change.revision)
                    }
                    MemoryDomainSync.ENTITY_MEMORY -> {
                        memoryDomainSync?.applyRemotePayload(
                            entityId = change.entityId,
                            operation = change.operation,
                            payload = change.payload,
                            revision = change.revision,
                        )
                        rememberRevision(change.entityType, change.entityId, change.revision)
                    }
                    FavoriteDomainSync.ENTITY_FAVORITE -> {
                        favoriteDomainSync?.applyRemotePayload(
                            entityId = change.entityId,
                            operation = change.operation,
                            payload = change.payload,
                            revision = change.revision,
                        )
                        rememberRevision(change.entityType, change.entityId, change.revision)
                    }
                    FileDomainSync.ENTITY_FILE -> {
                        fileDomainSync?.applyRemotePayload(
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
        forceApply: Boolean = false,
    ) {
        withRemoteApply {
            var settings = settingsStore.settingsFlow.value
            when (entityType) {
                SettingsDomainSync.ENTITY_SETTING -> {
                    if (operation != "delete") {
                        // Server conflict/bootstrap payload is the setting row itself
                        // ({key,value,revision}) OR the mutation wrapper ({value: ...}).
                        val value = (payload as? JsonObject)?.get("value")
                            ?: return@withRemoteApply
                        settings = SyncableSettings.applyKey(settings, entityId, value)
                        settingsStore.update(settings)
                    }
                }
                SettingsDomainSync.ENTITY_ASSISTANT -> {
                    if (!forceApply && hasPendingOutbox(SettingsDomainSync.ENTITY_ASSISTANT, entityId)) {
                        Log.d(TAG, "skip remote assistant $entityId; local outbox pending")
                        return@withRemoteApply
                    }
                    val list = settings.assistants.toMutableList()
                    if (operation == "delete") {
                        list.removeAll { it.id.toString() == entityId }
                    } else {
                        val obj = payload as? JsonObject
                        // Accept nested {payload: Assistant} or flat Assistant body.
                        val body = obj?.get("payload") ?: payload
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
                MessageNodeDomainSync.ENTITY_MESSAGE_NODE -> {
                    messageNodeDomainSync?.applyRemotePayload(
                        entityId = entityId,
                        operation = operation,
                        payload = payload,
                        revision = revision ?: 0L,
                    )
                }
                MemoryDomainSync.ENTITY_MEMORY -> {
                    memoryDomainSync?.applyRemotePayload(
                        entityId = entityId,
                        operation = operation,
                        payload = payload,
                        revision = revision ?: 0L,
                    )
                }
                FavoriteDomainSync.ENTITY_FAVORITE -> {
                    favoriteDomainSync?.applyRemotePayload(
                        entityId = entityId,
                        operation = operation,
                        payload = payload,
                        revision = revision ?: 0L,
                    )
                }
                FileDomainSync.ENTITY_FILE -> {
                    fileDomainSync?.applyRemotePayload(
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

    private fun outboxPushPriority(entityType: String): Int = when (entityType) {
        SettingsDomainSync.ENTITY_SETTING -> 0
        FileDomainSync.ENTITY_FILE -> 1
        SettingsDomainSync.ENTITY_ASSISTANT -> 2
        MemoryDomainSync.ENTITY_MEMORY -> 3
        FolderDomainSync.ENTITY_FOLDER -> 4
        ConversationDomainSync.ENTITY_CONVERSATION -> 5
        MessageNodeDomainSync.ENTITY_MESSAGE_NODE -> 6
        FavoriteDomainSync.ENTITY_FAVORITE -> 7
        else -> 9
    }

    companion object {
        private const val TAG = "CloudSyncRepository"
        private const val SYNC_DEBOUNCE_MS = 800L
    }
}
