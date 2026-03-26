package me.rerere.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PtySession internal constructor(
    private val masterFd: Int,
    private val pid: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _output = MutableSharedFlow<SandboxOutput>(
        extraBufferCapacity = Int.MAX_VALUE,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val output: SharedFlow<SandboxOutput> = _output.asSharedFlow()

    private val _exitCode = CompletableDeferred<Int>()
    val exitCode: Deferred<Int> = _exitCode

    val isAlive: Boolean get() = !_exitCode.isCompleted

    init {
        val readJob = scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (true) {
                    val n = Pty.nativeRead(masterFd, buffer, buffer.size)
                    if (n <= 0) break
                    _output.emit(SandboxOutput.Stdout(String(buffer, 0, n)))
                }
            } catch (_: Exception) {
                // Stream closed
            }
        }

        scope.launch {
            readJob.join()
            val code = Pty.nativeWaitFor(pid)
            _output.emit(SandboxOutput.Exit(code))
            _exitCode.complete(code)
            Pty.nativeClose(masterFd)
            scope.cancel()
        }
    }

    suspend fun write(text: String) {
        if (!isAlive) return
        withContext(Dispatchers.IO) {
            val bytes = text.toByteArray()
            Pty.nativeWrite(masterFd, bytes, bytes.size)
        }
    }

    suspend fun writeLine(line: String) = write("$line\n")

    fun setWindowSize(rows: Int, cols: Int) {
        if (!isAlive) return
        Pty.nativeSetWindowSize(masterFd, rows, cols)
    }

    fun kill() {
        if (!isAlive) return
        Pty.nativeKill(pid, 15) // SIGTERM
        scope.launch {
            delay(500)
            if (!_exitCode.isCompleted) {
                Pty.nativeKill(pid, 9) // SIGKILL
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 4096
    }
}
