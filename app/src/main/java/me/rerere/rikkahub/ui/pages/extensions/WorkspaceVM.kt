package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress

class WorkspaceVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _installProgress = MutableStateFlow<Map<String, RootfsInstallProgress>>(emptyMap())
    val installProgress = _installProgress.asStateFlow()

    private val _installErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val installErrors = _installErrors.asStateFlow()

    fun create(name: String) {
        viewModelScope.launch {
            repository.create(name)
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            repository.rename(workspace.id, name)
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            repository.delete(workspace.id)
        }
    }

    fun setShellEnabled(workspace: WorkspaceEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.setShellEnabled(workspace.id, enabled)
        }
    }

    fun installRootfs(workspace: WorkspaceEntity, url: String) {
        viewModelScope.launch {
            _installErrors.update { it - workspace.id }
            runCatching {
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.update { it + (workspace.id to progress) }
                }
            }.onFailure { error ->
                _installErrors.update { it + (workspace.id to (error.message ?: "Rootfs 安装失败")) }
            }
            _installProgress.update { it - workspace.id }
        }
    }

    fun dismissInstallError(workspaceId: String) {
        _installErrors.update { it - workspaceId }
    }
}
