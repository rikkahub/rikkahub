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

// Max chars retained per stream, so a runaway command cannot OOM the app or blow out the LLM context.
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
        // The caller thread was interrupted (e.g. coroutine cancellation via runInterruptible);
        // kill the process so the command does not keep running.
        destroyForcibly()
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
                    // Past the cap, keep draining to EOF and discard: otherwise a full pipe blocks
                    // the child process and it can never exit.
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
            // On a forced kill (timeout/cancel) the stream is closed and a blocked read throws
            // InterruptedIOException etc.; keep what was read. The exception must not escape, or the
            // thread's default handler crashes the app.
        }
    }.apply { start() }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
