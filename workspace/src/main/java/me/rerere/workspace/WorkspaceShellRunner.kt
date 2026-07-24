package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
    fun executeRaw(context: WorkspaceShellContext, maxBytes: Int, outputStream: java.io.OutputStream): RawCommandResult
}

data class RawCommandResult(
    val exitCode: Int,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun executeRaw(
        context: WorkspaceShellContext,
        maxBytes: Int,
        outputStream: java.io.OutputStream,
    ): RawCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readRawResult(context.timeoutMillis, maxBytes, outputStream, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdinWriter?.join(1_000)
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
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

fun Process.readRawResult(
    timeoutMillis: Long,
    maxBytes: Int,
    targetOutputStream: java.io.OutputStream,
    stdin: ByteArray? = null,
): RawCommandResult {
    val stdout = ByteStreamCollector(inputStream, targetOutputStream, maxBytes)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdinWriter?.join(1_000)
        val stdoutFinished = stdout.join(5_000)
        val stderrFinished = stderr.join(1_000)

        // 优先处理明确的目标流写入异常
        stdout.writeError?.let { throw it }

        // 如果进程未超时且未强杀，从 stdout 读过程发生的异常应抛出；若超时强杀导致读取中断，则保留 timeout 逻辑
        if (finished && stdout.readError != null) {
            throw stdout.readError!!
        }

        val timedOut = !finished || !stdoutFinished || !stderrFinished

        return RawCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stderr = stderr.text(),
            timedOut = timedOut,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        destroyForcibly()
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }
}

private class ByteStreamCollector(
    private val stream: InputStream,
    private val outputStream: java.io.OutputStream,
    private val maxBytes: Int,
) {
    @Volatile
    var truncated = false
        private set

    @Volatile
    var readError: Throwable? = null
        private set

    @Volatile
    var writeError: Throwable? = null
        private set

    private var writtenBytes = 0

    private val thread = Thread {
        val buffer = ByteArray(8192)
        while (true) {
            val read = try {
                stream.read(buffer)
            } catch (e: Throwable) {
                readError = e
                break
            }
            if (read < 0) break
            val remaining = maxBytes - writtenBytes
            if (remaining > 0) {
                val toWrite = minOf(read, remaining)
                try {
                    outputStream.write(buffer, 0, toWrite)
                } catch (e: Throwable) {
                    writeError = e
                    break
                }
                writtenBytes += toWrite
            }
            if (read > remaining) {
                truncated = true
            }
        }
        try {
            outputStream.flush()
        } catch (e: Throwable) {
            if (writeError == null) writeError = e
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
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

    fun join(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }

    fun text(): String = synchronized(builder) { builder.toString() }
}
