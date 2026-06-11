package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

fun Process.readResult(timeoutMillis: Long): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
