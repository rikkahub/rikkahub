package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult

sealed interface MemoryFileState {
    data object Loading : MemoryFileState

    /** 文件不存在：路径不合法，或者已经被删掉 */
    data object Missing : MemoryFileState

    data class Loaded(val file: MemoryFile) : MemoryFileState
}

/**
 * 单个记忆文件的查看与编辑。
 *
 * [memoryId] 是记忆库的 id（助手 id 或全局记忆库），[path] 为 null 表示新建。
 */
class MemoryFileVM(
    private val memoryId: String,
    path: String?,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    // 工具调用里的路径是模型给的，可能没带开头的斜杠
    private val normalizedPath = path?.let(MemoryFile::normalizePath)

    val state: StateFlow<MemoryFileState> =
        if (normalizedPath == null) {
            flowOf(MemoryFileState.Missing)
        } else {
            memoryRepository.getFilesFlow(memoryId).map { files ->
                files.find { it.path == normalizedPath }?.let(MemoryFileState::Loaded) ?: MemoryFileState.Missing
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MemoryFileState.Loading)

    /** 新建文件，路径已被占用时返回 [MemoryWriteResult.Conflict] */
    fun create(path: String, content: String, onResult: (MemoryWriteResult) -> Unit) {
        viewModelScope.launch {
            onResult(memoryRepository.writeFile(memoryId, path, content, MemoryRepository.NEW_FILE_VERSION))
        }
    }

    fun save(content: String, onResult: (MemoryWriteResult) -> Unit) {
        val path = normalizedPath ?: return
        viewModelScope.launch {
            onResult(memoryRepository.writeFile(memoryId, path, content))
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val path = normalizedPath ?: return
        viewModelScope.launch {
            memoryRepository.deleteFile(memoryId, path)
            onDeleted()
        }
    }
}
