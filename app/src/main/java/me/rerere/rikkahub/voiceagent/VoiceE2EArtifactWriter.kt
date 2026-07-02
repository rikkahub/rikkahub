package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

enum class VoiceE2EArtifact(
    val fileName: String,
    val appendOnly: Boolean = false,
) {
    InputTranscript("input-transcript.txt"),
    OutputTranscript("output-transcript.txt"),
    HermesCall("hermes-call.txt"),
    HermesAnswer("hermes-answer.txt"),
    HermesEvents("hermes-events.ndjson", appendOnly = true),
    SessionJson("session.json"),
}

class VoiceE2EArtifactWriter private constructor(
    enabled: Boolean,
    rootDirectory: File,
    traceId: String?,
    scope: CoroutineScope?,
) {
    private val commands = if (enabled) {
        Channel<WriteCommand>(capacity = Channel.UNLIMITED)
    } else {
        null
    }
    private val activeTraceId = traceId?.takeIf { it.isSafeTraceDirectoryName() }
    private val baseDirectory = VoiceE2EArtifactPaths.rootDirectory(rootDirectory)
    private val directory = activeTraceId
        ?.let { File(baseDirectory, it) }
        ?: baseDirectory
    private val latestTraceFile = activeTraceId?.let { VoiceE2EArtifactPaths.latestTraceIdFile(rootDirectory) }
    private val pendingLock = Any()
    private val flushLock = Any()
    private val terminalWriteScope = if (enabled) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    } else {
        null
    }
    private val terminalWriteLock = Any()
    private var terminalWriteTail = completedWrite()
    private val pendingWrites = LinkedHashMap<VoiceE2EArtifact, String>()
    private val pendingAppends = mutableListOf<PendingAppend>()
    private var flushQueued = false

    init {
        val queue = commands
        if (queue != null && scope != null) {
            scope.launch(Dispatchers.IO) {
                for (command in queue) {
                    when (command) {
                        WriteCommand.CleanOldTraceDirectories -> cleanOldTraceDirectories()
                        WriteCommand.ClearAppendOnlyArtifacts -> clearAppendOnlyArtifacts()
                        WriteCommand.Flush -> flushPendingWrites()
                        is WriteCommand.Drain -> {
                            flushPendingWrites()
                            command.completed.complete(Unit)
                        }
                    }
                }
            }
            if (activeTraceId != null && queue.trySend(WriteCommand.CleanOldTraceDirectories).isFailure) {
                VoiceAgentLog.w(TAG, "artifact trace retention cleanup queue rejected")
            }
            if (queue.trySend(WriteCommand.ClearAppendOnlyArtifacts).isFailure) {
                VoiceAgentLog.w(TAG, "artifact append cleanup queue rejected")
            }
        }
    }

    operator fun invoke(artifact: VoiceE2EArtifact, content: String) = write(artifact, content)

    fun write(artifact: VoiceE2EArtifact, content: String) {
        val queue = commands ?: return
        if (artifact.appendOnly && content.containsLineBreak()) {
            VoiceAgentLog.w(TAG, "artifact append rejected multiline name=${artifact.fileName}")
            return
        }
        var shouldQueueFlush = false
        synchronized(pendingLock) {
            if (artifact.appendOnly) {
                pendingAppends += PendingAppend(artifact, content)
            } else {
                pendingWrites[artifact] = content
            }
            if (!flushQueued) {
                flushQueued = true
                shouldQueueFlush = true
            }
        }
        if (shouldQueueFlush && queue.trySend(WriteCommand.Flush).isFailure) {
            VoiceAgentLog.w(TAG, "artifact write queue rejected")
        }
    }

    fun writeTerminalSessionJson(content: String): CompletableDeferred<Unit> {
        val writerScope = terminalWriteScope ?: return completedWrite()
        synchronized(pendingLock) {
            pendingWrites.remove(VoiceE2EArtifact.SessionJson)
        }
        val completed = CompletableDeferred<Unit>()
        val previous = synchronized(terminalWriteLock) {
            terminalWriteTail.also {
                terminalWriteTail = completed
            }
        }
        writerScope.launch {
            runCatching { previous.await() }
            synchronized(flushLock) {
                synchronized(pendingLock) {
                    pendingWrites.remove(VoiceE2EArtifact.SessionJson)
                }
                writeArtifact(VoiceE2EArtifact.SessionJson, content, append = false)
            }
            completed.complete(Unit)
        }
        return completed
    }

    suspend fun drain() {
        val queue = commands ?: return
        val completed = CompletableDeferred<Unit>()
        queue.send(WriteCommand.Drain(completed))
        completed.await()
    }

    private fun clearAppendOnlyArtifacts() {
        VoiceE2EArtifact.entries.filter { it.appendOnly }.forEach { artifact ->
            runCatching {
                Files.deleteIfExists(File(directory, artifact.fileName).toPath())
            }.onFailure { error ->
                val message = (error.message ?: error.javaClass.simpleName).redactForVoiceAgentLog()
                VoiceAgentLog.w(TAG, "artifact append cleanup failed name=${artifact.fileName} message=$message")
            }
        }
    }

    private fun cleanOldTraceDirectories() {
        val traceId = activeTraceId ?: return
        runCatching {
            prepareActiveTraceDirectory()
            val traceDirectories = baseDirectory.listFiles()
                .orEmpty()
                .filter { child ->
                    child.isDirectory &&
                        !Files.isSymbolicLink(child.toPath()) &&
                        child.name.isSafeTraceDirectoryName()
                }
                .sortedWith(
                    compareByDescending<File> { if (it.name == traceId) 1 else 0 }
                        .thenByDescending { it.lastModified() }
                        .thenByDescending { it.name },
                )

            traceDirectories
                .drop(MAX_TRACE_ARTIFACT_DIRECTORIES)
                .forEach { it.deleteTraceDirectoryNoFollow() }
        }.onFailure { error ->
            val message = (error.message ?: error.javaClass.simpleName).redactForVoiceAgentLog()
            VoiceAgentLog.w(TAG, "artifact trace retention cleanup failed message=$message")
        }
    }

    private fun prepareActiveTraceDirectory() {
        directory.mkdirs()
        latestTraceFile?.let { file ->
            requireNotNull(file.parentFile).mkdirs()
            file.writeText(requireNotNull(activeTraceId))
        }
    }

    private fun writeArtifact(artifact: VoiceE2EArtifact, content: String, append: Boolean) {
        runCatching {
            prepareActiveTraceDirectory()
            val file = File(directory, artifact.fileName)
            if (append) {
                file.appendText("$content\n")
            } else {
                file.replaceTextAtomically(content)
            }
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            VoiceAgentLog.w(TAG, "artifact write failed name=${artifact.fileName} message=$message")
        }
    }

    private fun flushPendingWrites() {
        synchronized(flushLock) flush@{
            while (true) {
                val snapshots = synchronized(pendingLock) {
                    if (pendingWrites.isEmpty() && pendingAppends.isEmpty()) {
                        flushQueued = false
                        null
                    } else {
                        val writes = LinkedHashMap(pendingWrites)
                        val appends = pendingAppends.toList()
                        pendingWrites.clear()
                        pendingAppends.clear()
                        writes to appends
                    }
                } ?: return@flush
                val (writeSnapshot, appendSnapshot) = snapshots
                writeSnapshot.forEach { (artifact, content) ->
                    writeArtifact(artifact, content, append = false)
                }
                appendSnapshot.forEach { append ->
                    writeArtifact(append.artifact, append.content, append = true)
                }
            }
        }
    }

    private data class PendingAppend(
        val artifact: VoiceE2EArtifact,
        val content: String,
    )

    private sealed interface WriteCommand {
        object CleanOldTraceDirectories : WriteCommand
        object ClearAppendOnlyArtifacts : WriteCommand
        object Flush : WriteCommand
        data class Drain(val completed: CompletableDeferred<Unit>) : WriteCommand
    }

    companion object {
        fun disabled(): VoiceE2EArtifactWriter = VoiceE2EArtifactWriter(
            enabled = false,
            rootDirectory = File(""),
            traceId = null,
            scope = null,
        )

        fun create(
            enabled: Boolean,
            rootDirectory: File,
            traceId: String? = null,
            scope: CoroutineScope,
        ): VoiceE2EArtifactWriter = VoiceE2EArtifactWriter(
            enabled = enabled,
            rootDirectory = rootDirectory,
            traceId = traceId,
            scope = scope,
        )
    }
}

private fun File.replaceTextAtomically(content: String) {
    val parent = requireNotNull(parentFile)
    val temp = File.createTempFile("$name.", ".tmp", parent)
    try {
        temp.writeText(content)
        try {
            VoiceE2EAtomicMoveOperation.move(temp.toPath(), toPath(), true)
        } catch (error: AtomicMoveNotSupportedException) {
            VoiceE2EAtomicMoveOperation.move(temp.toPath(), toPath(), false)
        }
    } finally {
        if (temp.exists()) {
            temp.delete()
        }
    }
}

internal object VoiceE2EAtomicMoveOperation {
    var move: (source: Path, target: Path, atomic: Boolean) -> Path = { source, target, atomic ->
        if (atomic) {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } else {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

private fun String.containsLineBreak(): Boolean = contains('\n') || contains('\r')

private fun String.isSafeTraceDirectoryName(): Boolean =
    isNotBlank() &&
        this != "." &&
        this != ".." &&
        this != VoiceE2EArtifactPaths.LATEST_TRACE_ID_FILE_NAME &&
        SAFE_TRACE_DIRECTORY_NAME.matches(this)

private fun File.deleteTraceDirectoryNoFollow() {
    val rootPath = toPath().toAbsolutePath().normalize()
    Files.walkFileTree(
        rootPath,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                file.requireUnderTraceDirectory(rootPath)
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    throw exc
                }
                dir.requireUnderTraceDirectory(rootPath)
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun Path.requireUnderTraceDirectory(rootPath: Path) {
    val visitedPath = toAbsolutePath().normalize()
    if (!visitedPath.startsWith(rootPath)) {
        throw SecurityException("refusing to delete path outside trace directory")
    }
}

private val SAFE_TRACE_DIRECTORY_NAME = Regex("[A-Za-z0-9._-]+")
private const val MAX_TRACE_ARTIFACT_DIRECTORIES = 10
private const val TAG = "VoiceE2EArtifactWriter"

private fun completedWrite(): CompletableDeferred<Unit> =
    CompletableDeferred<Unit>().also { it.complete(Unit) }
