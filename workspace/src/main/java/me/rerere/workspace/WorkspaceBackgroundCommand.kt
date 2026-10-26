package me.rerere.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 在后台运行的命令, stdout 与 stderr 合并写入日志文件。
 *
 * 方法都是阻塞 IO, 调用方负责切到 IO 线程。
 */
interface WorkspaceBackgroundCommand {
    /** 日志文件在执行环境内的绝对路径, 模型可以用 shell 直接读取 */
    val logPath: String

    /** 阻塞到命令退出, 返回退出码 */
    fun waitFor(): Int

    /** 日志当前的字节数 */
    fun outputSize(): Long

    /** 从 [offset] 起读取至多 [maxBytes] 字节的日志 */
    fun readOutput(offset: Long, maxBytes: Int): ByteArray

    /** 终止命令及其派生的所有进程, 阻塞到进程退出 */
    fun kill()
}

/**
 * PRoot 内的后台命令。
 *
 * PRoot 会忽略 SIGTERM, 被 SIGKILL 后它跟踪的进程会脱离跟踪继续运行,
 * 所以不能直接 [Process.destroy]: 先给命令派生的进程发 SIGTERM, 留出宽限期让它们正常退出;
 * 仍未退出时给 PRoot 发 SIGQUIT, 由它对所有被跟踪的进程发 SIGKILL。
 * 进程号由 shell 启动时写入 [pidFile] (`$$ $PPID`), 即 shell 自身与 PRoot 的进程号。
 */
internal class ProotBackgroundCommand(
    private val process: Process,
    private val logFile: File,
    override val logPath: String,
    private val pidFile: File,
) : WorkspaceBackgroundCommand {
    override fun waitFor(): Int = process.waitFor()

    override fun outputSize(): Long = logFile.length()

    override fun readOutput(offset: Long, maxBytes: Int): ByteArray {
        if (!logFile.isFile) return ByteArray(0)
        return RandomAccessFile(logFile, "r").use { file ->
            val available = (file.length() - offset).coerceAtLeast(0)
            val buffer = ByteArray(minOf(available, maxBytes.toLong()).toInt())
            file.seek(offset)
            file.readFully(buffer)
            buffer
        }
    }

    override fun kill() {
        val pids = readPids()
        if (pids != null) {
            val (shellPid, prootPid) = pids
            // 命令本身先收到 SIGTERM; 直接结束 shell 会让 --kill-on-exit 立刻 SIGKILL 其余进程
            val tracees = traceesOf(prootPid)
            (tracees - shellPid).ifEmpty { listOf(shellPid) }.forEach { signal(it, OsConstants.SIGTERM) }
            if (process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS)) return
            signal(prootPid, OsConstants.SIGQUIT)
            if (process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)) return
            process.destroyForcibly()
            traceesOf(prootPid).plus(tracees).forEach { signal(it, OsConstants.SIGKILL) }
        } else {
            process.destroyForcibly()
        }
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    // 刚启动时 shell 可能还没写入进程号, 稍等片刻
    private fun readPids(): Pair<Int, Int>? {
        val deadline = System.currentTimeMillis() + PID_WAIT_MS
        while (true) {
            val pids = runCatching { pidFile.readText() }.getOrNull()
                ?.trim()
                ?.split(' ')
                ?.mapNotNull { it.toIntOrNull() }
            if (pids != null && pids.size == 2) return pids[0] to pids[1]
            if (!process.isAlive || System.currentTimeMillis() > deadline) return null
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TERMINATE_GRACE_MS = 3_000L
        const val KILL_WAIT_MS = 2_000L
        const val PID_WAIT_MS = 1_000L
    }
}

/** 被 [tracerPid] 跟踪的进程, 即 PRoot 内运行的所有进程 (包括脱离了进程树的守护进程) */
private fun traceesOf(tracerPid: Int): List<Int> =
    File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val tracer = runCatching {
            File(dir, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            }
        }.getOrNull()
        pid.takeIf { tracer == tracerPid }
    }

private fun signal(pid: Int, signal: Int) {
    try {
        Os.kill(pid, signal)
    } catch (_: ErrnoException) {
        // 进程已经退出
    }
}
