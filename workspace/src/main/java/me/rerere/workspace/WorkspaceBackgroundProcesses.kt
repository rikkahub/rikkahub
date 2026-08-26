package me.rerere.workspace

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val MAX_BG_OUTPUT_CHARS = 32 * 1024
const val MAX_BG_PROCESSES = 5
private const val MAX_ENTRIES_PER_ROOT = 16

/** Process-lifetime registry for background workspace shell processes. */
class WorkspaceBackgroundProcesses(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val idCounter = AtomicInteger(0)

    fun start(root: String, process: Process, command: String, cwd: String): BackgroundStatus {
        synchronized(entries) {
            val runningForRoot = entries.values.count { it.root == root && it.process.isAlive }
            if (runningForRoot >= MAX_BG_PROCESSES) {
                process.destroyForcibly()
                throw IllegalStateException(
                    "Too many running background processes for this workspace (max $MAX_BG_PROCESSES). Kill one with workspace_background_kill first."
                )
            }
            val entry = Entry(
                id = "bg_${idCounter.incrementAndGet()}",
                root = root,
                process = process,
                command = command,
                cwd = cwd,
                startedAtMillis = nowMillis(),
            )
            entries[entry.id] = entry
            evictOldestExited(root)
            return entry.toStatus()
        }
    }

    fun status(root: String, id: String): BackgroundStatus? {
        val entry = entries[id] ?: return null
        return if (entry.root == root) entry.toStatus() else null
    }

    fun list(root: String): List<BackgroundStatus> =
        entries.values
            .filter { it.root == root }
            .sortedBy { it.startedAtMillis }
            .map { it.toStatus() }

    fun kill(root: String, id: String): Boolean = synchronized(entries) {
        val entry = entries[id] ?: return@synchronized false
        if (entry.root != root) return@synchronized false
        entries.remove(id)
        entry.process.destroyForcibly()
        true
    }

    fun killAll(root: String) = synchronized(entries) {
        entries.values
            .filter { it.root == root }
            .forEach { entry -> entries.remove(entry.id)?.process?.destroyForcibly() }
    }

    private fun evictOldestExited(root: String) {
        val forRoot = entries.values.filter { it.root == root }
        val overflow = forRoot.size - MAX_ENTRIES_PER_ROOT
        if (overflow <= 0) return
        forRoot
            .filter { !it.process.isAlive }
            .sortedBy { it.startedAtMillis }
            .take(overflow)
            .forEach { entries.remove(it.id) }
    }

    private fun Entry.toStatus(): BackgroundStatus {
        val alive = process.isAlive
        if (!alive) {
            stdoutDrainer.join(1_000)
            stderrDrainer.join(1_000)
        }
        val (stdoutText, stdoutDropped) = stdout.snapshot()
        val (stderrText, stderrDropped) = stderr.snapshot()
        return BackgroundStatus(
            id = id,
            command = command,
            cwd = cwd,
            running = alive,
            exitCode = if (alive) null else process.exitValue(),
            startedAtMillis = startedAtMillis,
            stdout = stdoutText,
            stderr = stderrText,
            droppedStdout = stdoutDropped,
            droppedStderr = stderrDropped,
        )
    }

    private class Entry(
        val id: String,
        val root: String,
        val process: Process,
        val command: String,
        val cwd: String,
        val startedAtMillis: Long,
    ) {
        val stdout = TailBuffer(MAX_BG_OUTPUT_CHARS)
        val stderr = TailBuffer(MAX_BG_OUTPUT_CHARS)
        val stdoutDrainer = Drainer(process.inputStream, stdout)
        val stderrDrainer = Drainer(process.errorStream, stderr)
    }
}

class TailBuffer(private val maxChars: Int) {
    private val builder = StringBuilder()

    @Volatile
    var droppedChars: Long = 0
        private set

    fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(builder) {
            builder.append(text)
            val overflow = builder.length - maxChars
            if (overflow > 0) {
                builder.delete(0, overflow)
                droppedChars += overflow
            }
        }
    }

    fun snapshot(): Pair<String, Long> = synchronized(builder) { builder.toString() to droppedChars }
}

private class Drainer(stream: InputStream, private val buffer: TailBuffer) {
    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    buffer.append(String(chunk, 0, read))
                }
            }
        } catch (_: IOException) {
            // The process may be forcibly killed while a pipe read is blocked.
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

data class BackgroundStatus(
    val id: String,
    val command: String,
    val cwd: String,
    val running: Boolean,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val stdout: String,
    val stderr: String,
    val droppedStdout: Long,
    val droppedStderr: Long,
)
