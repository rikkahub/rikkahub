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
import me.rerere.rikkahub.data.model.SshJumpHostDraft
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
                            jumpHost = settings.jumpHost?.let { jump ->
                                JumpHostForm(
                                    enabled = true,
                                    host = jump.host,
                                    port = jump.port.toString(),
                                    username = jump.username,
                                    authType = jump.authType,
                                    hostKey = jump.hostKey,
                                    hasSavedPassword = jump.encryptedPassword != null,
                                    hasSavedPrivateKey = jump.encryptedPrivateKey != null,
                                )
                            } ?: JumpHostForm(),
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
            var new = transform(old)
            // 信任的主机密钥属于原来的地址, 地址变了必须重新确认
            val jumpChanged = new.jumpHost.enabled && old.jumpHost.enabled &&
                (new.jumpHost.host.trim() != old.jumpHost.host.trim() || new.jumpHost.port != old.jumpHost.port)
            if (jumpChanged) {
                new = new.copy(jumpHost = new.jumpHost.copy(hostKey = null), probeResult = null)
            }
            // 经跳板机时目标地址由跳板机解析, 开关跳板或换了跳板, 同一个地址也可能是另一台机器
            if (
                new.host.trim() != old.host.trim() || new.port != old.port ||
                new.jumpHost.enabled != old.jumpHost.enabled || jumpChanged
            ) {
                new = new.copy(hostKey = null, probeResult = null)
            }
            new
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
        if (state.value.toDraftOrNull()?.withoutHostKeys() != pending.draft.withoutHostKeys()) {
            _state.update { it.copy(pendingHostKey = null) }
            return
        }
        _state.update {
            if (pending.jumpHost) {
                it.copy(jumpHost = it.jumpHost.copy(hostKey = pending.actual), pendingHostKey = null)
            } else {
                it.copy(hostKey = pending.actual, pendingHostKey = null)
            }
        }
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
            val message = if (current.isTargetValid()) {
                R.string.workspace_ssh_editor_missing_jump_fields
            } else {
                R.string.workspace_ssh_editor_missing_fields
            }
            _state.update { it.copy(error = SshEditorError.Resource(message)) }
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
                    it.copy(
                        testing = false,
                        pendingHostKey = PendingHostKey(draft, e.hostKey, null, saveAfter, e.jumpHost),
                    )
                }
            } catch (e: SshHostKeyChangedException) {
                _state.update {
                    it.copy(
                        testing = false,
                        pendingHostKey = PendingHostKey(draft, e.actual, e.expected, saveAfter, e.jumpHost),
                    )
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

    private fun WorkspaceSshEditorState.isTargetValid(): Boolean =
        port.toPortOrNull() != null && host.isNotBlank() && username.isNotBlank()

    private fun WorkspaceSshEditorState.toDraftOrNull(): SshWorkspaceDraft? {
        val port = port.toPortOrNull() ?: return null
        if (host.isBlank() || username.isBlank()) return null
        val jump = if (jumpHost.enabled) {
            jumpHost.toDraftOrNull() ?: return null
        } else {
            null
        }
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
            jumpHost = jump,
        )
    }

    private fun JumpHostForm.toDraftOrNull(): SshJumpHostDraft? {
        val port = port.toPortOrNull() ?: return null
        if (host.isBlank() || username.isBlank()) return null
        return SshJumpHostDraft(
            host = host,
            port = port,
            username = username,
            authType = authType,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            hostKey = hostKey,
        )
    }

    private fun String.toPortOrNull(): Int? = trim().toIntOrNull()?.takeIf { it in 1..65535 }

    private fun SshWorkspaceDraft.withoutHostKeys() =
        copy(hostKey = null, jumpHost = jumpHost?.copy(hostKey = null))
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
    val jumpHost: JumpHostForm = JumpHostForm(),
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

/** 跳板机表单; 关掉开关时保留填过的内容, 保存时不写入 */
data class JumpHostForm(
    val enabled: Boolean = false,
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val hostKey: SshHostKey? = null,
    val hasSavedPassword: Boolean = false,
    val hasSavedPrivateKey: Boolean = false,
)

/** 表单自己的提示用字符串资源, 连接和保存失败时直接显示异常信息 */
sealed interface SshEditorError {
    data class Resource(@StringRes val id: Int) : SshEditorError

    data class Message(val text: String) : SshEditorError
}

/**
 * 等待用户确认的主机密钥, 属于 [draft] 这份配置; [expected] 不为空表示和之前信任的不一致,
 * [jumpHost] 表示是跳板机的密钥
 */
data class PendingHostKey(
    val draft: SshWorkspaceDraft,
    val actual: SshHostKey,
    val expected: SshHostKey?,
    val saveAfter: Boolean,
    val jumpHost: Boolean = false,
) {
    val address: String
        get() = if (jumpHost) {
            draft.jumpHost?.let { "${it.host}:${it.port}" }.orEmpty()
        } else {
            "${draft.host}:${draft.port}"
        }
}
