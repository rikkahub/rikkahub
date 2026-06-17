package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.ChatboxStreamingImportResult
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.common.state.UiState
import me.rerere.rikkahub.utils.shouldRethrowVmError
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid
import java.io.File

private const val TAG = "BackupVM"

internal interface ChatboxImportRunner {
    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
        onProvidersImported: suspend (List<ProviderSetting>) -> Unit,
        onConversation: suspend (Conversation) -> Unit,
    ): ChatboxStreamingImportResult
}

internal class BackupChatboxImportRunner : ChatboxImportRunner {
    override suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
        onProvidersImported: suspend (List<ProviderSetting>) -> Unit,
        onConversation: suspend (Conversation) -> Unit,
    ): ChatboxStreamingImportResult {
        return ChatboxImporter.importStreaming(
            file = file,
            assistantId = assistantId,
            providers = providers,
            onProvidersImported = onProvidersImported,
            onConversation = onConversation,
        )
    }
}

internal suspend fun runChatboxImport(
    importer: ChatboxImportRunner,
    file: File,
    assistantId: Uuid,
    providers: List<ProviderSetting>,
    persistProviders: suspend (List<ProviderSetting>) -> Unit,
    enableSystemPromptGate: suspend () -> Unit,
    insertConversation: suspend (Conversation) -> Unit,
    conversationExists: suspend (Uuid) -> Boolean,
    isSystemPromptEnabled: Boolean,
): ChatboxRestoreResult {
    var importedConversations = 0
    var skippedExistingConversations = 0
    var isSystemPromptEnabledNow = isSystemPromptEnabled

    val result = importer.importStreaming(
        file = file,
        assistantId = assistantId,
        providers = providers,
        onProvidersImported = { importedProviders ->
            persistProviders(importedProviders)
        },
        onConversation = { conversation ->
            if (conversationExists(conversation.id)) {
                skippedExistingConversations++
            } else {
                if (!isSystemPromptEnabledNow && !conversation.customSystemPrompt.isNullOrBlank()) {
                    enableSystemPromptGate()
                    isSystemPromptEnabledNow = true
                }

                insertConversation(conversation)
                importedConversations++
            }
        },
    )

    return ChatboxRestoreResult(
        importedProviders = result.providers.size,
        importedConversations = importedConversations,
        skippedExistingConversations = skippedExistingConversations,
        skippedImageParts = result.skippedImageParts,
        skippedEmptyMessages = result.skippedEmptyMessages,
    )
}

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    /**
     * Explicit state for the imperative backup/restore operations, keyed by [BackupOperationState.Kind]
     * so one flow drives every button. The backup *list* stays as the [UiState] flows above; this is a
     * separate concern (issue #105). Raw exception text is mapped to a safe string here, at the VM
     * boundary, and cancellation never becomes an error.
     */
    private val _operationState = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val operationState = _operationState.asStateFlow()

    /** Return to [BackupOperationState.Idle] after the UI has shown the terminal toast. */
    fun acknowledgeOperation() {
        _operationState.value = BackupOperationState.Idle
    }

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                backupListThrowableToState<List<WebDavBackupItem>>(it)?.let { state ->
                    webDavBackupItems.emit(state)
                } ?: throw it
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    /**
     * Run one backup/restore operation under [BackupOperationState], on [viewModelScope] so its
     * lifecycle is the VM's, not a transient UI scope. [onSuccess] runs only on the success path
     * (UI navigation / list refresh). Cancellation is rethrown — it must never surface as an Error
     * toast — while any other failure is mapped to a safe user-facing string.
     */
    private fun runOperation(
        kind: BackupOperationState.Kind,
        runningMessage: String? = null,
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (_operationState.value is BackupOperationState.Running) return
        viewModelScope.launch {
            _operationState.value = BackupOperationState.Running(kind, runningMessage)
            try {
                block()
                _operationState.value = BackupOperationState.Success(kind)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Backup operation $kind failed", e)
                _operationState.value = backupThrowableToState(kind, e)
            }
        }
    }

    fun backup(onSuccess: () -> Unit = {}) = runOperation(
        kind = BackupOperationState.Kind.WebDavBackup,
        onSuccess = onSuccess,
    ) {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    fun restore(item: WebDavBackupItem, onSuccess: () -> Unit = {}) = runOperation(
        kind = BackupOperationState.Kind.WebDavRestore,
        runningMessage = item.displayName,
        onSuccess = onSuccess,
    ) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    /**
     * Export a backup, then hand the prepared file to [consume] (the caller streams it to the
     * user-chosen destination). The whole flow — prepare + copy-out — is one operation so a
     * copy-out failure is still reported as a backup error, not a silent half-success.
     */
    fun exportToFile(onSuccess: () -> Unit = {}, consume: suspend (File) -> Unit) = runOperation(
        kind = BackupOperationState.Kind.LocalExport,
        onSuccess = onSuccess,
    ) {
        val file = webDavSync.prepareBackupFile(settings.value.webDavConfig.copy())
        try {
            consume(file)
            recordBackupTime()
        } finally {
            file.delete()
        }
    }

    /**
     * Restore from a local file the caller has already materialized via [prepare] (URI -> temp).
     * Driving the prepare + restore + cleanup as one operation keeps the temp file cleanup on every
     * terminal path, including failure.
     */
    fun restoreFromLocalFile(onSuccess: () -> Unit = {}, prepare: suspend () -> File) = runOperation(
        kind = BackupOperationState.Kind.LocalRestore,
        onSuccess = onSuccess,
    ) {
        val file = prepare()
        try {
            webDavSync.restoreFromLocalFile(file, settings.value.webDavConfig)
        } finally {
            file.delete()
        }
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        val targetAssistantId = settings.value.assistantId
        val isSystemPromptEnabled = settings.value.assistants
            .firstOrNull { it.id == targetAssistantId }
            ?.allowConversationSystemPrompt ?: false

        val result = runChatboxImport(
            importer = BackupChatboxImportRunner(),
            file = file,
            assistantId = targetAssistantId,
            providers = settings.value.providers,
            persistProviders = { importedProviders ->
                settingsStore.update { current ->
                    current.copy(
                        providers = importedProviders + current.providers,
                    )
                }
            },
            enableSystemPromptGate = {
                settingsStore.update { current ->
                    current.copy(
                        assistants = current.assistants.map { assistant ->
                            if (assistant.id == targetAssistantId) {
                                assistant.copy(allowConversationSystemPrompt = true)
                            } else {
                                assistant
                            }
                        },
                    )
                }
            },
            insertConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
            },
            conversationExists = { conversationId ->
                conversationRepository.existsConversationById(conversationId)
            },
            isSystemPromptEnabled = isSystemPromptEnabled,
        )

        return result.also {
            Log.i(
                TAG,
                "restoreFromChatBox: import ${it.importedProviders} providers, " +
                    "${it.importedConversations} conversations, skip ${it.skippedExistingConversations} existing, " +
                    "drop ${it.skippedImageParts} images"
            )
        }
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                backupListThrowableToState<List<S3BackupItem>>(it)?.let { state ->
                    s3BackupItems.emit(state)
                } ?: throw it
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    fun backupToS3(onSuccess: () -> Unit = {}) = runOperation(
        kind = BackupOperationState.Kind.S3Backup,
        onSuccess = onSuccess,
    ) {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    fun restoreFromS3(item: S3BackupItem, onSuccess: () -> Unit = {}) = runOperation(
        kind = BackupOperationState.Kind.S3Restore,
        runningMessage = item.displayName,
        onSuccess = onSuccess,
    ) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

/**
 * Map a backup/restore [Throwable] to a safe, user-facing message. Pure (no Android), so the
 * "never leak raw throwable text past a fallback" contract is unit-testable on the JVM. A blank or
 * null message collapses to a generic fallback instead of an empty toast.
 */
fun backupErrorMessage(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: "Backup operation failed"

/**
 * Map a backup/restore terminal outcome to [BackupOperationState]. A null [throwable] is success;
 * any other throwable becomes a safe [BackupOperationState.Error]. Pure, so the contract — including
 * that the error message is the mapped safe string, never the raw throwable — is JVM-testable.
 */
fun backupThrowableToState(
    kind: BackupOperationState.Kind,
    throwable: Throwable?,
): BackupOperationState =
    if (throwable == null) BackupOperationState.Success(kind)
    else BackupOperationState.Error(kind, backupErrorMessage(throwable))

/**
 * Map a backup-*list*-load [Throwable] to the terminal [UiState] the list flow should emit, OR null
 * when the throwable must be rethrown instead of reported. Cancellation (per [shouldRethrowVmError])
 * returns null so the caller rethrows it — a navigated-away list load must never surface as
 * [UiState.Error]; every other throwable maps to [UiState.Error]. Pure (no Android, no coroutines),
 * so the rethrow-vs-report contract is JVM-testable, mirroring [backupThrowableToState].
 */
fun <T> backupListThrowableToState(throwable: Throwable): UiState<T>? =
    if (shouldRethrowVmError(throwable)) null else UiState.Error(throwable)
