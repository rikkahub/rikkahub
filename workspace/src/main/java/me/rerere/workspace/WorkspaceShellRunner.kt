package me.rerere.workspace

import java.io.File
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

fun Process.readResult(timeoutMillis: Long): WorkspaceCommandResult {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val stdoutThread = inputStream.readTextAsync(stdout)
    val stderrThread = errorStream.readTextAsync(stderr)
    val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    if (!finished) {
        destroyForcibly()
    }
    stdoutThread.join(1_000)
    stderrThread.join(1_000)
    return WorkspaceCommandResult(
        exitCode = if (finished) exitValue() else -1,
        stdout = stdout.toString(),
        stderr = stderr.toString(),
        timedOut = !finished,
    )
}

private fun InputStream.readTextAsync(target: StringBuilder): Thread = Thread {
    bufferedReader().use { reader ->
        val buffer = CharArray(4096)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            target.append(buffer, 0, read)
        }
    }
}.apply { start() }
