package me.rerere.workspace.ssh

import com.jcraft.jsch.ChannelExec
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程主机上带 PTY 的交互式 shell, 供终端使用。
 *
 * [inputStream] 是 shell 的输出 (PTY 已合并 stderr), [outputStream] 写入 shell 的输入。
 * 打开期间一直占用所在的连接, 不会被连接池当成空闲断开; [close] 后释放。
 * 除 [inputStream] / [outputStream] 外的方法都会走网络, 不能在主线程调用。
 */
class SshShell internal constructor(
    private val channel: ChannelExec,
    val inputStream: InputStream,
    val outputStream: OutputStream,
    private val connection: SshConnection,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** shell 结束时 SSH 连接是否已经断开, 用来区分正常退出和掉线 */
    val connectionLost: Boolean
        get() = !connection.isConnected

    fun resize(columns: Int, rows: Int) {
        channel.setPtySize(columns, rows, 0, 0)
    }

    /** 阻塞到 shell 结束, 返回退出码; 没收到退出码 (掉线、被信号结束或被 [close]) 时返回 null */
    fun waitFor(): Int? {
        while (!channel.isClosed) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return channel.exitStatus.takeIf { it >= 0 }
    }

    /** 关闭 channel, 远程 shell 随 PTY 挂断退出 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            channel.disconnect()
        } finally {
            onClose()
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
