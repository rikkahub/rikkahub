package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager

import java.io.File
import java.util.UUID

enum class LayoutMode { CARD, LIST, COMPACT }

enum class SortMode { DATE_DESC, DATE_ASC, NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC }

@OptIn(ExperimentalCoroutinesApi::class)
class SettingFilesVM(
    private val filesManager: FilesManager,
) : ViewModel() {

    private val _events = MutableSharedFlow<FilesEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<FilesEvent> = _events

    private val _selectedFolder = MutableStateFlow(FileFolders.UPLOAD)
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    private val _layoutMode = MutableStateFlow(LayoutMode.CARD)
    val layoutMode: StateFlow<LayoutMode> = _layoutMode.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.DATE_DESC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _refreshSignal = MutableStateFlow(0L)

    val files: StateFlow<List<ManagedFileEntity>> = combine(
        _selectedFolder, _sortMode, _refreshSignal
    ) { folder, sort, _ -> Triple(folder, sort, Unit) }
        .flatMapLatest { (folder, sort, _) ->
            when (folder) {
                FileFolders.IMAGES -> flowOf(listImageFilesAsEntities())
                else -> filesManager.observe(folder)
            }.map { list -> sortFiles(list, sort) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fileCount: StateFlow<Int> = files.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val fileTotalSize: StateFlow<Long> = files.map { it.sumOf { file -> file.sizeBytes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun selectFolder(folder: String) {
        _selectedFolder.value = folder
        exitMultiSelectMode()
    }

    fun setLayoutMode(mode: LayoutMode) {
        _layoutMode.value = mode
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun enterMultiSelectMode(id: Long) {
        _isMultiSelectMode.value = true
        _selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: Long) {
        _selectedIds.value = if (id in _selectedIds.value) {
            _selectedIds.value - id
        } else {
            _selectedIds.value + id
        }
    }

    fun selectAll() {
        _selectedIds.value = files.value.map { it.id }.toSet()
    }

    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedIds.value = emptySet()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val isImagesFolder = _selectedFolder.value == FileFolders.IMAGES
            val fileMap = files.value.associateBy { it.id }

            val deleted = _selectedIds.value.count { id ->
                if (isImagesFolder) {
                    val entity = fileMap[id]
                    entity != null && withContext(Dispatchers.IO) {
                        File(entity.relativePath).delete()
                    }
                } else {
                    filesManager.delete(id, deleteFromDisk = true)
                }
            }

            if (isImagesFolder && deleted > 0) {
                _refreshSignal.value++
            }
            exitMultiSelectMode()
            _events.emit(FilesEvent.DeleteResult(deleted))
        }
    }

    suspend fun deleteFile(id: Long): Boolean = deleteFileById(id)

    fun syncFolder() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val folder = _selectedFolder.value
                // IMAGES folder: sync button is hidden in UI (reserved for future
                // incremental indexing).  This path exists as a safety net in case
                // sync is invoked programmatically — it just refreshes the list.
                if (folder == FileFolders.IMAGES) {
                    _refreshSignal.value++
                    _events.emit(FilesEvent.SyncResult(0))
                } else {
                    val result = filesManager.syncFolder(folder)
                    _events.emit(FilesEvent.SyncResult(result.inserted))
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun getFileForEntity(entity: ManagedFileEntity): File {
        return if (entity.folder == FileFolders.IMAGES) {
            File(entity.relativePath)
        } else {
            filesManager.getFile(entity)
        }
    }

    fun getImagePaths(): List<String> {
        return files.value
            .filter { it.mimeType.startsWith("image/") }
            .map { getFileForEntity(it).absolutePath }
    }

    private suspend fun deleteFileById(id: Long, refresh: Boolean = true): Boolean {
        return if (_selectedFolder.value == FileFolders.IMAGES) {
            val entity = files.value.find { it.id == id }
            if (entity != null) {
                val deleted = withContext(Dispatchers.IO) {
                    File(entity.relativePath).delete()
                }
                if (deleted && refresh) _refreshSignal.value++
                deleted
            } else false
        } else {
            filesManager.delete(id, deleteFromDisk = true)
        }
    }

    private suspend fun listImageFilesAsEntities(): List<ManagedFileEntity> = withContext(Dispatchers.IO) {
        filesManager.listImageFiles()
            .sortedByDescending { it.lastModified() }
            .map { file ->
            // Generate a stable per-process ID from the file path.
            // UUID.nameUUIDFromBytes produces a deterministic MD5-based UUID;
            // we take the most significant 64 bits, which are sufficient for
            // distinguishing files within a single folder view.
            // These entities are transient (never persisted to Room); the ID
            // only needs to be unique per file path within the current folder.
            val uuid = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray())
            ManagedFileEntity(
                id = uuid.mostSignificantBits,
                folder = FileFolders.IMAGES,
                relativePath = file.absolutePath,
                displayName = file.name,
                mimeType = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream",
                sizeBytes = file.length(),
                createdAt = file.lastModified(),
                updatedAt = file.lastModified(),
            )
        }
    }

    private fun sortFiles(
        list: List<ManagedFileEntity>,
        mode: SortMode,
    ): List<ManagedFileEntity> = when (mode) {
        SortMode.DATE_DESC -> list.sortedByDescending { it.createdAt }
        SortMode.DATE_ASC -> list.sortedBy { it.createdAt }
        SortMode.NAME_ASC -> list.sortedBy { it.displayName.lowercase() }
        SortMode.NAME_DESC -> list.sortedByDescending { it.displayName.lowercase() }
        SortMode.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
        SortMode.SIZE_ASC -> list.sortedBy { it.sizeBytes }
    }
}

sealed class FilesEvent {
    data class DeleteResult(val count: Int) : FilesEvent()
    data class SyncResult(val count: Int) : FilesEvent()
}
