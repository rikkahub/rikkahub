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
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    /** Per-knowledge-base ingestion progress in [0,1]; absent key = idle. */
    private val _ingestProgress = MutableStateFlow<Map<Uuid, Float>>(emptyMap())
    val ingestProgress: StateFlow<Map<Uuid, Float>> = _ingestProgress.asStateFlow()

    sealed interface Event {
        data class IngestDone(val kbId: Uuid, val fileName: String, val chunkCount: Int) : Event
        data class IngestFailed(val kbId: Uuid, val reason: String) : Event
    }

    private val _events = MutableStateFlow<Event?>(null)
    val events: StateFlow<Event?> = _events.asStateFlow()

    fun consumeEvent() {
        _events.value = null
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
            val rawFileName = filesManager.getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "document"
            // Display-only metadata: strip path separators / control chars / .. and bound length. The
            // raw provider name never touches the filesystem (temp path is uuid + sanitized ext).
            val fileName = RagIngestLimits.sanitizeDisplayName(rawFileName)
            val mime = filesManager.getFileMimeType(uri) ?: "application/octet-stream"
            // Authoritative type gate: reject unsupported files BEFORE copying bytes to temp, so an
            // unsupported binary never lands on disk only to be rejected downstream. The picker MIME
            // filter is advisory (providers can report octet-stream), so this check is the real gate.
            if (RagDocumentPolicy.resolve(fileName, mime) == null) {
                _events.value = Event.IngestFailed(kbId, "Unsupported file type")
                return@launch
            }
            val tempFile = try {
                copyToTemp(uri, rawFileName)
            } catch (e: RagIngestLimitException) {
                // Oversized input is a distinct, expected outcome — surface "too large" rather than
                // the generic "failed to read".
                _events.value = Event.IngestFailed(kbId, e.message ?: "File is too large")
                return@launch
            }
            if (tempFile == null) {
                _events.value = Event.IngestFailed(kbId, "Failed to read file")
                return@launch
            }
            try {
                _ingestProgress.value = _ingestProgress.value + (kbId to 0f)
                val result = ingestUseCase(
                    knowledgeBaseId = kbId,
                    file = tempFile,
                    fileName = fileName,
                    mime = mime,
                    onProgress = { p -> _ingestProgress.value = _ingestProgress.value + (kbId to p) },
                )
                when (result) {
                    is IngestKnowledgeBaseUseCase.Result.Success -> {
                        settingsStore.update { current ->
                            current.copy(
                                knowledgeBases = current.knowledgeBases.map { kb ->
                                    if (kb.id == kbId) kb.copy(documents = kb.documents + result.document) else kb
                                }
                            )
                        }
                        _events.value = Event.IngestDone(kbId, fileName, result.document.chunkCount)
                    }

                    IngestKnowledgeBaseUseCase.Result.EmbeddingUnavailable ->
                        _events.value = Event.IngestFailed(
                            kbId,
                            "Set an OpenAI-compatible embedding model for this knowledge base first"
                        )

                    IngestKnowledgeBaseUseCase.Result.EmptyDocument ->
                        _events.value = Event.IngestFailed(kbId, "No extractable text in this file")

                    IngestKnowledgeBaseUseCase.Result.UnsupportedType ->
                        _events.value = Event.IngestFailed(kbId, "Unsupported file type")

                    is IngestKnowledgeBaseUseCase.Result.ParseFailed ->
                        // Surface that parsing failed without embedding the raw parser error text.
                        _events.value = Event.IngestFailed(kbId, "Could not parse this file")

                    is IngestKnowledgeBaseUseCase.Result.Rejected ->
                        // Resource limit hit (text/chunks too large); reason is a safe diagnostic.
                        _events.value = Event.IngestFailed(kbId, result.reason)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Ingestion rolled back its partial chunks (see IngestKnowledgeBaseUseCase) and
                // rethrew; surface the failure to the user instead of letting the coroutine die
                // silently with orphaned UI state.
                _events.value = Event.IngestFailed(kbId, e.message ?: "Ingestion failed")
            } finally {
                _ingestProgress.value = _ingestProgress.value - kbId
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
