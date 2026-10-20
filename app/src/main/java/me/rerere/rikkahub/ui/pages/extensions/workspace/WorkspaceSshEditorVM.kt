package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey
import me.rerere.workspace.ssh.SshHostKeyChangedException
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshUnknownHostKeyException

class WorkspaceSshEditorVM(
    private val id: String?,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceSshEditorState(loading = id != null))
    val state = _state.asStateFlow()

    val isNew: Boolean = id == null

    init {
        if (id != null) {
            viewModelScope.launch {
                val workspace = repository.getById(id)
                val settings = workspace?.sshSettings()
                _state.update { state ->
                    if (workspace == null || settings == null) {
                        state.copy(
                            loading = false,
                            error = SshEditorError.Resource(R.string.workspace_ssh_editor_not_found)
                        )
                    } else {
                        state.copy(
                            loading = false,
                            name = workspace.name,
                            host = settings.host,
                            port = settings.port.toString(),
                            username = settings.username,
                            authType = settings.authType,
                            remoteDir = settings.remoteDir,
                            hostKey = settings.hostKey,
                            hasSavedPassword = settings.encryptedPassword != null,
                            hasSavedPrivateKey = settings.encryptedPrivateKey != null,
                        )
                    }
                }
            }
        }
    }

    fun update(transform: (WorkspaceSshEditorState) -> WorkspaceSshEditorState) {
        _state.update { old ->
            // 测试和保存针对的是开始时的表单快照, 期间不接受修改
            if (old.busy) return@update old
            val new = transform(old)
            // 信任的主机密钥属于原来的地址, 地址变了必须重新确认
            if (new.host.trim() != old.host.trim() || new.port != old.port) {
                new.copy(hostKey = null, probeResult = null)
            } else {
                new
            }
        }
    }

    fun testConnection() = runProbe(saveAfter = false)

    /** 保存前先测试连接, 顺便确认主机密钥; 连接失败时询问是否仍然保存 */
    fun save() = runProbe(saveAfter = true)

    /** 保存测试失败的那份配置 */
    fun saveAnyway() {
        val draft = state.value.saveAnywayDraft ?: return
        _state.update { it.copy(error = null, saveAnywayDraft = null) }
        persist(draft)
    }

    fun trustHostKey() {
        val pending = state.value.pendingHostKey ?: return
        // 指纹属于测试时的那份配置, 表单变了就不能套用到新地址上
        if (state.value.toDraftOrNull()?.copy(hostKey = null) != pending.draft.copy(hostKey = null)) {
            _state.update { it.copy(pendingHostKey = null) }
            return
        }
        _state.update { it.copy(hostKey = pending.actual, pendingHostKey = null) }
        runProbe(saveAfter = pending.saveAfter)
    }

    fun dismissHostKey() {
        _state.update { it.copy(pendingHostKey = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, saveAnywayDraft = null) }
    }

    private fun runProbe(saveAfter: Boolean) {
        val current = state.value
        if (current.busy) return
        val draft = current.toDraftOrNull() ?: run {
            _state.update { it.copy(error = SshEditorError.Resource(R.string.workspace_ssh_editor_missing_fields)) }
            return
        }
        _state.update { it.copy(testing = true, error = null, saveAnywayDraft = null) }
        viewModelScope.launch {
            try {
                val result = repository.probeSsh(id, draft)
                // 没填工作目录时默认用远程用户的主目录
                val tested = draft.copy(remoteDir = draft.remoteDir.ifBlank { result.homeDir })
                _state.update { it.copy(testing = false, probeResult = result, remoteDir = tested.remoteDir) }
                if (saveAfter) persist(tested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshUnknownHostKeyException) {
                _state.update {
                    it.copy(testing = false, pendingHostKey = PendingHostKey(draft, e.hostKey, null, saveAfter))
                }
            } catch (e: SshHostKeyChangedException) {
                _state.update {
                    it.copy(testing = false, pendingHostKey = PendingHostKey(draft, e.actual, e.expected, saveAfter))
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        testing = false,
                        probeResult = null,
                        error = SshEditorError.Message(e.message ?: e.javaClass.simpleName),
                        saveAnywayDraft = draft.takeIf { saveAfter },
                    )
                }
            }
        }
    }

    private fun persist(draft: SshWorkspaceDraft) {
        if (draft.remoteDir.isBlank()) {
            _state.update { it.copy(error = SshEditorError.Resource(R.string.workspace_ssh_editor_missing_remote_dir)) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repository.saveSsh(id, draft)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        saving = false,
                        error =
                        e.message?.let(SshEditorError::Message)
                            ?: SshEditorError.Resource(R.string.workspace_ssh_editor_save_failed)
                    )
                }
            }
        }
    }

    private fun WorkspaceSshEditorState.toDraftOrNull(): SshWorkspaceDraft? {
        val port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || username.isBlank()) return null
        return SshWorkspaceDraft(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            remoteDir = remoteDir.trim(),
            hostKey = hostKey,
        )
    }
}

data class WorkspaceSshEditorState(
    val loading: Boolean = false,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val remoteDir: String = "",
    val hostKey: SshHostKey? = null,
    // 编辑已有工作区时, 已保存的凭据可以留空沿用
    val hasSavedPassword: Boolean = false,
    val hasSavedPrivateKey: Boolean = false,
    val testing: Boolean = false,
    val saving: Boolean = false,
    val probeResult: SshProbeResult? = null,
    val pendingHostKey: PendingHostKey? = null,
    val error: SshEditorError? = null,
    /** 保存前测试失败的配置, 用户可以选择仍然保存它 */
    val saveAnywayDraft: SshWorkspaceDraft? = null,
    val saved: Boolean = false,
) {
    val busy: Boolean
        get() = loading || testing || saving

    val canSaveAnyway: Boolean
        get() = saveAnywayDraft != null
}

/** 表单自己的提示用字符串资源, 连接和保存失败时直接显示异常信息 */
sealed interface SshEditorError {
    data class Resource(@StringRes val id: Int) : SshEditorError

    data class Message(val text: String) : SshEditorError
}

/** 等待用户确认的主机密钥, 属于 [draft] 这份配置; [expected] 不为空表示和之前信任的不一致 */
data class PendingHostKey(
    val draft: SshWorkspaceDraft,
    val actual: SshHostKey,
    val expected: SshHostKey?,
    val saveAfter: Boolean,
)
