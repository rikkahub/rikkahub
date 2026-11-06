package me.rerere.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

private const val KILL_WAIT_MS = 2_000L
private const val KILL_ROUNDS = 10
private const val KILL_ROUND_WAIT_MS = 200L
private const val PID_WAIT_MS = 1_000L

/**
 * 终止 PRoot 及其中运行的所有进程, 阻塞到 PRoot 退出。
 *
 * PRoot 被 SIGKILL 后它跟踪的进程会脱离跟踪继续运行, 所以不能直接 [Process.destroyForcibly];
 * 也不能靠 PRoot 收到 SIGQUIT 后清理: 它从应用进程继承了屏蔽 SIGQUIT 的信号掩码, 信号一直挂起。
 * 做法是在 PRoot 还活着时 SIGKILL 它跟踪的所有进程, 最后一个退出后 PRoot 自己退出;
 * 命令可能正在派生新进程, 所以多扫几轮。
 * [graceMillis] 大于 0 时先给命令派生的进程发 SIGTERM, 留出这段时间让它们正常退出。
 *
 * 进程号由 shell 启动时写入 [pidFile] (`$$ $PPID`), 即 shell 自身与 PRoot 的进程号。
 */
internal fun terminateProot(process: Process, pidFile: File, graceMillis: Long = 0) {
    val pids = readPids(process, pidFile)
    if (pids == null) {
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        return
    }
    val (shellPid, prootPid) = pids
    if (graceMillis > 0) {
        // 命令本身先收到 SIGTERM; 直接结束 shell 会让 --kill-on-exit 立刻 SIGKILL 其余进程
        (traceesOf(prootPid) - shellPid).ifEmpty { listOf(shellPid) }.forEach { signal(it, OsConstants.SIGTERM) }
        if (process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) return
    }
    repeat(KILL_ROUNDS) {
        traceesOf(prootPid).forEach { signal(it, OsConstants.SIGKILL) }
        if (process.waitFor(KILL_ROUND_WAIT_MS, TimeUnit.MILLISECONDS)) return
    }
    process.destroyForcibly()
    process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
}

// 刚启动时 shell 可能还没写入进程号, 稍等片刻
private fun readPids(process: Process, pidFile: File): Pair<Int, Int>? {
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
