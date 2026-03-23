package me.rerere.sandbox

import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A long-lived sandbox session backed by a running [Process].
 *
 * **Output**: [output] is a hot [SharedFlow]. Stdout and stderr events are emitted as they arrive;
 * a final [SandboxOutput.Exit] is emitted when both streams close and the process exits.
 * After that the SharedFlow keeps its value but emits no more events. Collect with a lifecycle-aware
 * scope (Compose LaunchedEffect, viewModelScope, etc.) so the coroutine is cancelled automatically
 * when no longer needed.
 *
 * **Exit**: [exitCode] is a [Deferred] that resolves to the process exit code. Await it to know
 * when the process has finished.
 *
 * **Input**: write to stdin via [write] or [writeLine].
 *
 * **Cleanup**: call [kill] when the session is no longer needed; the scope is also cancelled
 * automatically once [exitCode] resolves.
 */
class SandboxSession internal constructor(private val process: Process) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stdin: OutputStream = process.outputStream

    private val _output = MutableSharedFlow<SandboxOutput>(
        extraBufferCapacity = Int.MAX_VALUE,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Hot shared flow of all output events. Never completes; use [exitCode] to detect termination. */
    val output: SharedFlow<SandboxOutput> = _output.asSharedFlow()

    private val _exitCode = CompletableDeferred<Int>()

    /** Resolves to the process exit code after the process has exited. */
    val exitCode: Deferred<Int> = _exitCode

    val isAlive: Boolean get() = process.isAlive

    init {
        val stdoutJob = scope.launch {
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(BUFFER_SIZE)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    _output.emit(SandboxOutput.Stdout(String(buf, 0, n)))
                }
            }
        }
        val stderrJob = scope.launch {
            process.errorStream.bufferedReader().use { reader ->
                val buf = CharArray(BUFFER_SIZE)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    _output.emit(SandboxOutput.Stderr(String(buf, 0, n)))
                }
            }
        }
        scope.launch {
            stdoutJob.join()
            stderrJob.join()
            process.waitFor()
            val code = process.exitValue()
            _output.emit(SandboxOutput.Exit(code))
            _exitCode.complete(code)
            scope.cancel()
        }
    }

    /**
     * Write raw text to the process stdin. No-op if the process has already exited.
     */
    suspend fun write(text: String) {
        if (!isAlive) return
        withContext(Dispatchers.IO) {
            stdin.write(text.toByteArray())
            stdin.flush()
        }
    }

    /**
     * Write a line to the process stdin (appends `\n`). No-op if the process has already exited.
     */
    suspend fun writeLine(line: String) = write("$line\n")

    /**
     * Send SIGTERM, wait briefly, then SIGKILL if still alive.
     */
    fun kill() {
        if (!isAlive) return
        process.destroy()
        scope.launch {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 4096
    }
}
