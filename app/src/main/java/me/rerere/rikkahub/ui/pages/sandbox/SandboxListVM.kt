package me.rerere.rikkahub.ui.pages.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sandbox.SandboxInfo
import me.rerere.rikkahub.data.sandbox.SandboxManager
import me.rerere.sandbox.RootFsInstaller
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val ARCHLINUX_ROOTFS_URL =
    "https://github.com/termux/proot-distro/releases/download/v4.34.2/archlinux-aarch64-pd-v4.34.2.tar.xz"

sealed class SandboxInstallState {
    data object Idle : SandboxInstallState()
    data class Downloading(val progress: Float) : SandboxInstallState() // 0..1, -1 = indeterminate
    data object Installing : SandboxInstallState()
    data class Error(val message: String) : SandboxInstallState()
}

data class SandboxListItem(
    val info: SandboxInfo,
    val rootfsInstalled: Boolean,
    val installState: SandboxInstallState = SandboxInstallState.Idle,
)

class SandboxListVM(
    private val manager: SandboxManager,
    private val okHttpClient: OkHttpClient,
    private val context: Context,
) : ViewModel() {
    private val installStates = MutableStateFlow<Map<String, SandboxInstallState>>(emptyMap())

    val sandboxes = manager.listFlow()
        .combine(installStates) { infos, states ->
            infos.map { info ->
                SandboxListItem(
                    info = info,
                    rootfsInstalled = manager.isRootfsInstalled(info.id),
                    installState = states[info.id] ?: SandboxInstallState.Idle,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.create(name)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.delete(id)
        }
    }

    fun installRootfs(sandboxId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempFile = context.cacheDir.resolve("sandbox-rootfs-$sandboxId.tar.xz")
            try {
                // Download
                updateInstallState(sandboxId, SandboxInstallState.Downloading(-1f))
                val request = Request.Builder().url(ARCHLINUX_ROOTFS_URL).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()

                var bytesRead = 0L
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            bytesRead += n
                            val progress = if (contentLength > 0) bytesRead.toFloat() / contentLength else -1f
                            updateInstallState(sandboxId, SandboxInstallState.Downloading(progress))
                        }
                    }
                }

                // Install
                updateInstallState(sandboxId, SandboxInstallState.Installing)
                val rootfsDir = manager.rootfsDir(sandboxId)
                rootfsDir.mkdirs()
                RootFsInstaller().installTarXz(tempFile, rootfsDir)

                updateInstallState(sandboxId, SandboxInstallState.Idle)
            } catch (e: Exception) {
                updateInstallState(sandboxId, SandboxInstallState.Error(e.message ?: "未知错误"))
            } finally {
                tempFile.delete()
            }
        }
    }

    fun clearError(sandboxId: String) {
        updateInstallState(sandboxId, SandboxInstallState.Idle)
    }

    private fun updateInstallState(sandboxId: String, state: SandboxInstallState) {
        installStates.update { current -> current + (sandboxId to state) }
    }
}
