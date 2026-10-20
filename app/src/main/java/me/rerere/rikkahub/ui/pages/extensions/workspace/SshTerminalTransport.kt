package me.rerere.rikkahub.ui.pages.extensions.workspace

import com.termux.terminal.TerminalTransport
import me.rerere.workspace.ssh.SshShell
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 把远程 [SshShell] 接到终端会话上。
 *
 * 调整尺寸和关闭都要走网络, 而终端在主线程调用它们, 所以统一交给一个后台线程按顺序执行。
 */
internal class SshTerminalTransport(
    /** 连接目标, 如 user@host:22, 连接期间显示在终端里 */
    private val address: String,
    private val openShell: (columns: Int, rows: Int) -> SshShell,
) : TerminalTransport {
    private val control = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ssh-terminal-control").apply { isDaemon = true }
    }

    @Volatile
    private var shell: SshShell? = null

    @Volatile
    private var killed = false

    @Volatile
    private var remoteExitStatus: Int? = null

    @Volatile
    private var connectionLost = false

    override fun start(columns: Int, rows: Int) {
        val opened = openShell(columns, rows)
        shell = opened
        // 连接期间标签页已被关掉: kill() 那时还拿不到 shell, 由这里收尾
        if (killed) {
            opened.close()
            throw IOException("Terminal closed")
        }
    }

    override fun getInputStream(): InputStream = requireShell().inputStream

    override fun getOutputStream(): OutputStream = requireShell().outputStream

    override fun setWindowSize(columns: Int, rows: Int) {
        val shell = shell ?: return
        runControl { shell.resize(columns, rows) }
    }

    override fun waitFor(): Int {
        val shell = requireShell()
        val status = shell.waitFor()
        remoteExitStatus = status
        connectionLost = status == null && shell.connectionLost
        return status ?: -1
    }

    override fun kill() {
        killed = true
        val shell = shell ?: return
        runControl { shell.close() }
    }

    override fun close() {
        val shell = shell
        runControl { shell?.close() }
        control.shutdown()
    }

    override fun describeStart(): String = "Connecting to $address..."

    override fun describeExit(exitStatus: Int): String = when {
        remoteExitStatus != null -> super.describeExit(exitStatus)
        connectionLost -> "Connection lost"
        else -> "Session closed"
    }

    private fun requireShell(): SshShell = shell ?: error("SSH shell is not started")

    private fun runControl(block: () -> Unit) {
        try {
            control.execute { runCatching(block) }
        } catch (_: RejectedExecutionException) {
            // 已经 close, shell 也已关闭
        }
    }
}
