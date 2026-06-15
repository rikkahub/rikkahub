package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult

    /**
     * Start [context]'s command WITHOUT blocking on its result and hand back a [ShellRunHandle] that
     * OWNS the spawned [Process] (issue #291, PR-1). This is the root of the design proposal's
     * STOP_IS_DETACH_NOT_KILL fix: today [execute] hides the process and `readResult` `destroyForcibly`s
     * it on `InterruptedException`, so a caller can only kill, never detach. With the process owned by
     * the handle, PR-2's coordinator can detach-rather-than-kill on a user stop.
     *
     * Differences from [execute] that are pure hardening (no foreground-behaviour change):
     *  - stdout/stderr are streamed to [outputFile] (the app-private tail source `workspace_shell_tail`
     *    will read in PR-2) WHILE the same bounded in-memory buffers are retained for the inline
     *    byte-compatible [ShellRunHandle.await] return.
     *  - process stdin is CLOSED immediately after start (today it is never closed), so a command that
     *    reads stdin gets EOF instead of blocking forever.
     *  - a size watchdog kills the run if [outputFile] grows past [sizeCapBytes]
     *    ([ShellKillReason.KilledSize]); today's cap is only in-memory collector retention.
     */
    fun startShellRun(
        context: WorkspaceShellContext,
        outputFile: File,
        sizeCapBytes: Long = DEFAULT_OUTPUT_SIZE_CAP_BYTES,
    ): ShellRunHandle
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

/**
 * A live handle on a started shell run (issue #291). It owns the spawned process; awaiting it yields
 * the same [WorkspaceCommandResult] the blocking path produced, killing it is explicit and records a
 * [ShellKillReason], and [tail]/[byteCount] expose the redirected output for a progress poll.
 */
interface ShellRunHandle {
    /**
     * Block until the process exits OR its foreground timeout elapses, returning today's
     * [WorkspaceCommandResult]. On timeout the process is force-killed, exactly as the old
     * `readResult(timeout)` did. Idempotent: a second [await] returns the already-computed result.
     */
    fun await(): WorkspaceCommandResult

    /**
     * Force-kill the process with an explicit [reason] (the seam PR-2's hardTimeout / size-cap paths
     * use). The reason is exposed via [killReason]. A no-op if the process already exited.
     */
    fun kill(reason: ShellKillReason)

    /** The reason this run was force-killed, or null if it exited on its own / is still running. */
    val killReason: ShellKillReason?

    /** Bytes written to the output file so far (the size-watchdog and progress-poll cursor). */
    val byteCount: Long

    /** The trailing [maxBytes] of the redirected output, decoded as UTF-8 (best effort). */
    fun tail(maxBytes: Int): String

    /** Opaque process metadata (pid when the platform exposes it), for diagnostics. Never null-asserted. */
    val pidMeta: String?
}

/** The explicit kill reasons the [ShellRunHandle] surfaces (issue #291 design proposal terminal set). */
enum class ShellKillReason {
    /** The output file grew past the size cap. */
    KilledSize,

    /** A hard timeout (foreground or detached) elapsed. */
    KilledTimeout,

    /** An explicit user request (PR-2 abort path). */
    KilledUser,
}

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = startProcess(context)
        return process.readResult(context.timeoutMillis)
    }

    override fun startShellRun(
        context: WorkspaceShellContext,
        outputFile: File,
        sizeCapBytes: Long,
    ): ShellRunHandle = ProcessShellRunHandle.start(startProcess(context), context.timeoutMillis, outputFile, sizeCapBytes)

    private fun startProcess(context: WorkspaceShellContext): Process =
        ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// Max chars retained per stream, so a runaway command cannot OOM the app or blow out the LLM context.
const val MAX_OUTPUT_CHARS = 128 * 1024

// Cap on the redirected output FILE (issue #291). Larger than the in-memory char cap because the file
// is the durable tail source, but still bounded so a runaway producer cannot fill the disk.
const val DEFAULT_OUTPUT_SIZE_CAP_BYTES = 8L * 1024 * 1024

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

/**
 * The concrete [ShellRunHandle]: owns one spawned [Process] (issue #291). It reproduces the blocking
 * path's [readResult] semantics for [await] (so the default foreground call stays byte-compatible —
 * same bounded char collectors, same timeout/exit fields), and adds the three hardening behaviours
 * [WorkspaceShellRunner.startShellRun] documents: stdin closed up front, output streamed to a file,
 * and a size watchdog.
 *
 * The in-memory byte-compat buffer reuses the unchanged [StreamCollector] (char-based, so a multibyte
 * char is never split at a 4 KiB boundary the way a raw byte chunk would be); a SEPARATE byte-level
 * [ShellOutputFileSink] mirrors the raw bytes to the durable output file. The two read independent
 * stream copies via [TeeInputStream], so neither path's draining starves the other.
 */
internal class ProcessShellRunHandle private constructor(
    private val process: Process,
    private val timeoutMillis: Long,
    outputFile: File,
    private val sizeCapBytes: Long,
) : ShellRunHandle {

    private val fileSink: ShellOutputFileSink = ShellOutputFileSink(outputFile)
    private val stdoutCollector: StreamCollector
    private val stderrCollector: StreamCollector

    // The output sink is owned by the two stream pumps that WRITE to it, not by await(): once both have
    // drained to EOF the sink is closed, so a handle that is never await()ed still releases the file
    // descriptor instead of leaking it. await() also closes it (idempotent) for the join-timeout case.
    private val pumpsRemaining = AtomicInteger(2)

    @Volatile
    private var killReasonInternal: ShellKillReason? = null

    @Volatile
    private var cachedResult: WorkspaceCommandResult? = null

    private val resultLock = Any()

    init {
        // Close stdin immediately: today the process's stdin is never closed, so a command that reads
        // it (e.g. `cat` with no file arg) blocks forever. EOF lets it terminate. Closing can throw if
        // the process already died — there is no recoverable state to act on, so the close-only catch
        // is intentional, not error-hiding.
        runCatching { process.outputStream.close() }
        // Tee each stream: the char collector keeps the bounded in-memory buffer (byte-compat) while
        // every byte it reads is also written to the file sink. The last pump to finish closes the sink.
        fileSink.fileCapBytes = sizeCapBytes
        stdoutCollector = StreamCollector(TeeInputStream(process.inputStream, fileSink), onExit = ::onPumpExit)
        stderrCollector = StreamCollector(TeeInputStream(process.errorStream, fileSink), onExit = ::onPumpExit)
    }

    private fun onPumpExit() {
        if (pumpsRemaining.decrementAndGet() == 0) fileSink.close()
    }

    override val killReason: ShellKillReason?
        get() = killReasonInternal

    override val byteCount: Long
        get() = fileSink.byteCount

    // Opaque process identity for diagnostics. `Process.pid()` is not on this module's bootclasspath
    // (compileSdk strips it), and reflecting into the Android impl is brittle; the toString() form
    // ("Process[pid=N, ...]") is stable enough for a diagnostics-only field and never null-asserted.
    override val pidMeta: String? = process.toString()

    override fun tail(maxBytes: Int): String = fileSink.tail(maxBytes)

    override fun kill(reason: ShellKillReason) {
        if (process.isAlive) {
            // Record the reason BEFORE destroying, so an await racing the kill observes it.
            killReasonInternal = reason
            process.destroyForcibly()
        }
    }

    override fun await(): WorkspaceCommandResult {
        cachedResult?.let { return it }
        synchronized(resultLock) {
            cachedResult?.let { return it }
            val result = awaitOnce()
            cachedResult = result
            return result
        }
    }

    private fun awaitOnce(): WorkspaceCommandResult {
        try {
            // Poll the process in slices so the size watchdog can fire between waits, but NEVER grant
            // time past the deadline: the slice is capped to the time remaining, so a sub-poll timeout is
            // honored to the millisecond — byte-compatible with readResult's single waitFor(timeout).
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var finished = false
            while (true) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    // Deadline reached: mirror readResult returning the current termination status —
                    // already-exited counts as finished, still-running falls to the timeout kill below.
                    finished = !process.isAlive
                    break
                }
                val sliceMs = minOf(
                    WATCHDOG_POLL_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                )
                if (process.waitFor(sliceMs, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
                if (fileSink.byteCount > sizeCapBytes && killReasonInternal == null) {
                    kill(ShellKillReason.KilledSize)
                }
            }
            if (!finished && process.isAlive) {
                // Timeout (or size-cap kill mid-wait): force-kill, tagging a plain timeout when no
                // explicit reason was set. Matches the old readResult timeout branch.
                if (killReasonInternal == null) killReasonInternal = ShellKillReason.KilledTimeout
                process.destroyForcibly()
                process.waitFor(1_000, TimeUnit.MILLISECONDS)
            }
            stdoutCollector.join(1_000)
            stderrCollector.join(1_000)
            fileSink.close()
            return WorkspaceCommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdoutCollector.text(),
                stderr = stderrCollector.text(),
                // timedOut is reserved for a clean hard-timeout, byte-compatible with readResult's
                // `!finished`: a size-cap or explicit user kill is NOT a timeout.
                timedOut = !finished && killReasonInternal == ShellKillReason.KilledTimeout,
                truncated = stdoutCollector.truncated || stderrCollector.truncated,
            )
        } catch (e: InterruptedException) {
            // The awaiting thread was interrupted. PR-1 preserves today's kill-on-interrupt for the
            // foreground path (PR-2's coordinator detaches by NOT calling await under interruption).
            process.destroyForcibly()
            fileSink.close()
            throw e
        }
    }

    internal companion object {
        const val WATCHDOG_POLL_MILLIS = 50L

        /**
         * Build a handle that OWNS [process]. Opening the output sink can fail (e.g. an uncreatable
         * output path); the process is already started, so destroy it before rethrowing — otherwise it
         * would leak with no handle to kill it.
         */
        fun start(
            process: Process,
            timeoutMillis: Long,
            outputFile: File,
            sizeCapBytes: Long,
        ): ProcessShellRunHandle = try {
            ProcessShellRunHandle(process, timeoutMillis, outputFile, sizeCapBytes)
        } catch (t: Throwable) {
            process.destroyForcibly()
            throw t
        }
    }
}

/**
 * A [ShellRunHandle] over a run that never started a process (e.g. the PRoot rootfs is missing): the
 * terminal [WorkspaceCommandResult] is already known, so [await] just returns it and the kill/tail
 * operations are no-ops. Keeps a precheck failure byte-compatible with the old [WorkspaceShellRunner.execute]
 * path through the new seam.
 */
internal class PreResolvedShellRunHandle(
    private val result: WorkspaceCommandResult,
) : ShellRunHandle {
    override fun await(): WorkspaceCommandResult = result
    override fun kill(reason: ShellKillReason) = Unit
    override val killReason: ShellKillReason? = null
    override val byteCount: Long = 0L
    override fun tail(maxBytes: Int): String = ""
    override val pidMeta: String? = null
}

/**
 * Reads from [source] and mirrors every byte read to [sink] before returning it — so the wrapping
 * [StreamCollector] keeps its in-memory byte-compat buffer while the durable output file receives the
 * identical bytes. A child must never block on a full pipe, so the sink write is on the same read path
 * the collector already drains to EOF.
 */
private class TeeInputStream(
    private val source: InputStream,
    private val sink: ShellOutputFileSink,
) : InputStream() {
    override fun read(): Int {
        val b = source.read()
        if (b >= 0) sink.write(byteArrayOf(b.toByte()), 1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = source.read(b, off, len)
        if (read > 0) sink.write(b.copyOfRange(off, off + read), read)
        return read
    }

    override fun close() = source.close()
}

/**
 * Mirrors the redirected output to an app-private file while tracking the byte count (the
 * size-watchdog cursor) and serving a bounded [tail]. Append-only; written from both stream pumps, so
 * every method is synchronized on the same monitor as the file write.
 */
private class ShellOutputFileSink(private val file: File) {
    private val lock = Any()
    private val out: OutputStream

    /**
     * Total bytes the command has produced (the size-watchdog cursor) — NOT capped, so the watchdog
     * still observes a runaway producer even after the file write stops. The FILE itself is capped at
     * [fileCapBytes]: once the on-disk size would exceed it, further bytes are counted but DROPPED, so
     * the persisted file stays bounded regardless of how fast the producer races the kill (the
     * watchdog poll has latency; the sink-level cap is the hard bound).
     */
    @Volatile
    var byteCount: Long = 0L
        private set

    @Volatile
    var fileCapBytes: Long = DEFAULT_OUTPUT_SIZE_CAP_BYTES

    private var bytesWritten: Long = 0L

    // Latched once a file write fails: the durable-tail mirror is best-effort and must never throw back
    // into the pump, so after a failure it stops writing (byteCount keeps counting for the watchdog).
    @Volatile
    private var fileWriteFailed: Boolean = false

    init {
        file.parentFile?.mkdirs()
        out = file.outputStream().buffered()
    }

    fun write(buffer: ByteArray, length: Int) {
        synchronized(lock) {
            byteCount += length
            if (fileWriteFailed) return
            val room = fileCapBytes - bytesWritten
            if (room > 0) {
                val toWrite = minOf(length.toLong(), room).toInt()
                // Best-effort: a tail-file IO failure (e.g. disk full) must NOT propagate into the pump
                // thread, or the pump dies, the child's pipe fills, and the COMMAND hangs to its timeout.
                // Latch the failure and drop the mirror; the command keeps running and completing.
                try {
                    out.write(buffer, 0, toWrite)
                    bytesWritten += toWrite
                } catch (_: IOException) {
                    fileWriteFailed = true
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }

    fun tail(maxBytes: Int): String = synchronized(lock) {
        runCatching { out.flush() }
        if (!file.exists()) return ""
        val length = file.length()
        if (length <= maxBytes) return file.readBytes().toString(Charsets.UTF_8)
        file.inputStream().use { stream ->
            stream.skip(length - maxBytes)
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    }
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
    private val onExit: () -> Unit = {},
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
        } finally {
            // Signal the owner that this pump drained — the sink can close once both pumps finish, so an
            // abandoned (never-await()ed) handle still releases the file descriptor.
            onExit()
        }
    }.apply { start() }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
