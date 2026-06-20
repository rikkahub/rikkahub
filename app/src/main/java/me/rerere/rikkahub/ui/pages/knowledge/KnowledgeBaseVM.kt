package me.rerere.rikkahub.ui.pages.knowledge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDAO
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.rag.IngestKnowledgeBaseUseCase
import me.rerere.rikkahub.data.rag.KnowledgeBase
import me.rerere.rikkahub.data.rag.RagIngestLimitException
import me.rerere.rikkahub.data.rag.RagDocumentPolicy
import me.rerere.rikkahub.data.rag.RagIngestLimits
import me.rerere.rikkahub.data.rag.RagIngestState
import java.io.File
import kotlin.uuid.Uuid

class KnowledgeBaseVM(
    private val application: Application,
    private val settingsStore: SettingsStore,
    private val ingestUseCase: IngestKnowledgeBaseUseCase,
    private val filesManager: FilesManager,
    private val knowledgeChunkDao: KnowledgeChunkDAO,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, settingsStore.settingsFlow.value)

    /**
     * Explicit single-document ingest state: replaces the old `Map<Uuid, Float>` progress map plus a
     * transient event pair, which split one operation's status across two flows. Only one ingest runs
     * at a time (the picker is hidden while [RagIngestState.Running]), so a single value suffices.
     */
    private val _ingestState = MutableStateFlow<RagIngestState>(RagIngestState.Idle)
    val ingestState: StateFlow<RagIngestState> = _ingestState.asStateFlow()

    /** Return to [RagIngestState.Idle] after the UI has shown the terminal toast. */
    fun consumeIngestState() {
        _ingestState.value = RagIngestState.Idle
    }

    fun createKnowledgeBase(name: String, embeddingModelId: Uuid?) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    knowledgeBases = current.knowledgeBases + KnowledgeBase(
                        name = name.ifBlank { "Knowledge Base" },
                        embeddingModelId = embeddingModelId,
                    )
                )
            }
        }
    }

    fun updateKnowledgeBase(kb: KnowledgeBase) {
        viewModelScope.launch {
            val previous = settingsStore.settingsFlow.value.knowledgeBases.firstOrNull { it.id == kb.id }
            // Stored chunk vectors are produced by the KB's embedding model; a query vector from a new
            // model would be ranked against (or dimension-mismatch against) old-model vectors. So a
            // model change invalidates every existing chunk — purge them and reset the manifest,
            // forcing re-ingestion under the new model.
            val embeddingModelChanged =
                previous != null && previous.embeddingModelId != kb.embeddingModelId
            val effectiveKb = if (embeddingModelChanged) kb.copy(documents = emptyList()) else kb
            if (embeddingModelChanged) {
                withContext(Dispatchers.IO) {
                    knowledgeChunkDao.deleteByKb(kb.id.toString())
                }
            }
            settingsStore.update { current ->
                current.copy(
                    knowledgeBases = current.knowledgeBases.map { if (it.id == kb.id) effectiveKb else it }
                )
            }
        }
    }

    fun deleteKnowledgeBase(kb: KnowledgeBase) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                knowledgeChunkDao.deleteByKb(kb.id.toString())
            }
            settingsStore.update { current ->
                current.copy(
                    knowledgeBases = current.knowledgeBases.filterNot { it.id == kb.id },
                    // detach from any assistant referencing it
                    assistants = current.assistants.map {
                        if (it.knowledgeBaseId == kb.id) it.copy(knowledgeBaseId = null) else it
                    }
                )
            }
        }
    }

    fun deleteDocument(kbId: Uuid, documentId: Uuid) {
        viewModelScope.launch {
            val docIdStr = documentId.toString()
            withContext(Dispatchers.IO) {
                knowledgeChunkDao.deleteByDoc(kbId.toString(), docIdStr)
            }
            settingsStore.update { current ->
                current.copy(
                    knowledgeBases = current.knowledgeBases.map { kb ->
                        if (kb.id == kbId) kb.copy(documents = kb.documents.filterNot { it.id == documentId }) else kb
                    }
                )
            }
        }
    }

    fun ingest(kbId: Uuid, uri: Uri) {
        viewModelScope.launch {
            _ingestState.value = RagIngestState.Running(kbId, "", RagIngestState.Stage.Resolving, 0f)
            val rawFileName = filesManager.getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "document"
            // Display-only metadata: strip path separators / control chars / .. and bound length. The
            // raw provider name never touches the filesystem (temp path is uuid + sanitized ext).
            val fileName = RagIngestLimits.sanitizeDisplayName(rawFileName)
            val mime = filesManager.getFileMimeType(uri) ?: "application/octet-stream"
            // Authoritative type gate: reject unsupported files BEFORE copying bytes to temp, so an
            // unsupported binary never lands on disk only to be rejected downstream. The picker MIME
            // filter is advisory (providers can report octet-stream), so this check is the real gate.
            if (RagDocumentPolicy.resolve(fileName, mime) == null) {
                _ingestState.value = RagIngestState.Error(kbId, fileName, "Unsupported file type")
                return@launch
            }
            val tempFile = try {
                _ingestState.value =
                    RagIngestState.Running(kbId, fileName, RagIngestState.Stage.Copying, 0f)
                copyToTemp(uri, rawFileName)
            } catch (e: RagIngestLimitException) {
                // Oversized input is a distinct, expected outcome — surface "too large" rather than
                // the generic "failed to read".
                _ingestState.value =
                    RagIngestState.Error(kbId, fileName, e.message ?: "File is too large")
                return@launch
            }
            if (tempFile == null) {
                _ingestState.value = RagIngestState.Error(kbId, fileName, "Failed to read file")
                return@launch
            }
            try {
                val result = ingestUseCase(
                    knowledgeBaseId = kbId,
                    file = tempFile,
                    fileName = fileName,
                    mime = mime,
                    onStage = { stage, progress ->
                        _ingestState.value = RagIngestState.Running(kbId, fileName, stage, progress)
                    },
                )
                if (result is IngestKnowledgeBaseUseCase.Result.Success) {
                    settingsStore.update { current ->
                        current.copy(
                            knowledgeBases = current.knowledgeBases.map { kb ->
                                if (kb.id == kbId) kb.copy(documents = kb.documents + result.document) else kb
                            }
                        )
                    }
                }
                _ingestState.value = ingestResultToState(result, kbId, fileName)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Ingestion rolled back its partial chunks (see IngestKnowledgeBaseUseCase) and
                // rethrew; surface the failure to the user instead of letting the coroutine die
                // silently with orphaned UI state.
                _ingestState.value =
                    RagIngestState.Error(kbId, fileName, e.message ?: "Ingestion failed")
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun copyToTemp(uri: Uri, fileName: String): File? = withContext(Dispatchers.IO) {
        val dir = File(application.cacheDir, "rag_ingest").apply { mkdirs() }
        // UUID + sanitized extension only: the provider-supplied fileName can contribute nothing to
        // the path beyond an alnum extension, so separators / .. / huge names can't escape the dir.
        val target = RagIngestLimits.tempFileForDisplayName(dir, fileName)
        try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    // Abort + reject if the source exceeds the input cap, instead of filling the disk.
                    RagIngestLimits.copyLimited(input, output, RagIngestLimits.MAX_INPUT_BYTES)
                }
            } ?: error("Cannot open input stream for $uri")
            target
        } catch (e: RagIngestLimitException) {
            // Drop the partial oversized copy and re-throw so the caller surfaces "too large".
            target.delete()
            throw e
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            target.delete()
            null
        }
    }
}

/**
 * Map an ingest [IngestKnowledgeBaseUseCase.Result] to the terminal [RagIngestState]. Pure (no
 * VM/Room/Android), so the result-to-state contract — including the safe user-facing strings that
 * keep raw parser text out of the UI — is unit-testable on the JVM.
 */
fun ingestResultToState(
    result: IngestKnowledgeBaseUseCase.Result,
    kbId: Uuid,
    fileName: String,
): RagIngestState = when (result) {
    is IngestKnowledgeBaseUseCase.Result.Success ->
        RagIngestState.Success(kbId, fileName, result.document.chunkCount)

    IngestKnowledgeBaseUseCase.Result.EmbeddingUnavailable ->
        RagIngestState.Error(
            kbId,
            fileName,
            "Set an OpenAI-compatible embedding model for this knowledge base first"
        )

    IngestKnowledgeBaseUseCase.Result.EmptyDocument ->
        RagIngestState.Error(kbId, fileName, "No extractable text in this file")

    IngestKnowledgeBaseUseCase.Result.UnsupportedType ->
        RagIngestState.Error(kbId, fileName, "Unsupported file type")

    is IngestKnowledgeBaseUseCase.Result.ParseFailed ->
        // Surface that parsing failed without leaking the raw parser error text.
        RagIngestState.Error(kbId, fileName, "Could not parse this file")

    is IngestKnowledgeBaseUseCase.Result.Rejected ->
        // Resource limit hit (text/chunks too large); reason is a safe diagnostic.
        RagIngestState.Error(kbId, fileName, result.reason)
}
