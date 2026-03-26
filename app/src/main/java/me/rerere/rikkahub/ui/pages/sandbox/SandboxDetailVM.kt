package me.rerere.rikkahub.ui.pages.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sandbox.SandboxInfo
import me.rerere.rikkahub.data.sandbox.SandboxManager
import me.rerere.sandbox.PRootSandbox
import me.rerere.sandbox.PtySession
import me.rerere.sandbox.SandboxConfig
import me.rerere.sandbox.SandboxOutput
import me.rerere.sandbox.TerminalBuffer

enum class TerminalState { IDLE, RUNNING, EXITED }

class SandboxDetailVM(
    val sandboxId: String,
    private val manager: SandboxManager,
    private val context: Context,
) : ViewModel() {

    private val _sandboxInfo = MutableStateFlow<SandboxInfo?>(null)
    val sandboxInfo: SandboxInfo? get() = _sandboxInfo.value
    val isRootfsInstalled: Boolean get() = manager.isRootfsInstalled(sandboxId)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _terminalState = MutableStateFlow(TerminalState.IDLE)
    val terminalState: StateFlow<TerminalState> = _terminalState.asStateFlow()

    init {
        viewModelScope.launch {
            _sandboxInfo.value = manager.getById(sandboxId)
        }
    }

    private var session: PtySession? = null
    private var collectJob: Job? = null
    private val terminalBuffer = TerminalBuffer()

    fun startSession() {
        if (_terminalState.value == TerminalState.RUNNING) return
        terminalBuffer.clear()
        _output.value = ""
        _terminalState.value = TerminalState.RUNNING

        val proot = PRootSandbox(context)
        val rootfsDir = manager.rootfsDir(sandboxId)
        ensureBashProfile(rootfsDir)
        val config = SandboxConfig(
            rootfsDir = rootfsDir,
            workingDirectory = "/root",
        )
        val newSession = proot.startPtySession(config, guestCommand = listOf("/bin/bash", "-l"))
        session = newSession

        collectJob = viewModelScope.launch {
            newSession.output.collect { event ->
                when (event) {
                    is SandboxOutput.Stdout -> appendOutput(event.text)
                    is SandboxOutput.Stderr -> appendOutput(event.text)
                    is SandboxOutput.Exit -> {
                        appendOutput("\n[进程已退出，退出码: ${event.code}]\n")
                        _terminalState.value = TerminalState.EXITED
                    }
                }
            }
        }
    }

    fun sendLine(text: String) {
        viewModelScope.launch {
            session?.writeLine(text)
        }
    }

    fun killSession() {
        session?.kill()
        collectJob?.cancel()
        session = null
        _terminalState.value = TerminalState.IDLE
    }

    override fun onCleared() {
        session?.kill()
    }

    private fun appendOutput(text: String) {
        terminalBuffer.append(text)
        _output.value = terminalBuffer.getText()
    }

    private fun ensureBashProfile(rootfsDir: java.io.File) {
        val bashProfile = rootfsDir.resolve("root/.bash_profile")
        if (!bashProfile.exists()) {
            bashProfile.parentFile?.mkdirs()
            bashProfile.writeText("[ -f ~/.bashrc ] && . ~/.bashrc\n")
        }
    }
}
