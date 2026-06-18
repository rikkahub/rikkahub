package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

enum class VoiceE2EArtifact(
    val fileName: String,
    val appendOnly: Boolean = false,
) {
    InputTranscript("input-transcript.txt"),
    OutputTranscript("output-transcript.txt"),
    HermesCall("hermes-call.txt"),
    HermesAnswer("hermes-answer.txt"),
    HermesEvents("hermes-events.ndjson", appendOnly = true),
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
    private val baseDirectory = File(rootDirectory, "voice-e2e")
    private val directory = activeTraceId
        ?.let { File(baseDirectory, it) }
        ?: baseDirectory
    private val latestTraceFile = activeTraceId?.let { File(baseDirectory, "latest-trace-id.txt") }
    private val pendingLock = Any()
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
            directory.mkdirs()
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
                .forEach { it.deleteRecursively() }
        }.onFailure { error ->
            val message = (error.message ?: error.javaClass.simpleName).redactForVoiceAgentLog()
            VoiceAgentLog.w(TAG, "artifact trace retention cleanup failed message=$message")
        }
    }

    private fun writeArtifact(artifact: VoiceE2EArtifact, content: String, append: Boolean) {
        runCatching {
            directory.mkdirs()
            latestTraceFile?.let { file ->
                requireNotNull(file.parentFile).mkdirs()
                file.writeText(requireNotNull(activeTraceId))
            }
            val file = File(directory, artifact.fileName)
            if (append) {
                file.appendText("$content\n")
            } else {
                file.writeText(content)
            }
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            VoiceAgentLog.w(TAG, "artifact write failed name=${artifact.fileName} message=$message")
        }
    }

    private fun flushPendingWrites() {
        while (true) {
            val (writeSnapshot, appendSnapshot) = synchronized(pendingLock) {
                if (pendingWrites.isEmpty() && pendingAppends.isEmpty()) {
                    flushQueued = false
                    return
                }
                val writes = LinkedHashMap(pendingWrites)
                val appends = pendingAppends.toList()
                pendingWrites.clear()
                pendingAppends.clear()
                writes to appends
            }
            writeSnapshot.forEach { (artifact, content) ->
                writeArtifact(artifact, content, append = false)
            }
            appendSnapshot.forEach { append ->
                writeArtifact(append.artifact, append.content, append = true)
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

private fun String.containsLineBreak(): Boolean = contains('\n') || contains('\r')

private fun String.isSafeTraceDirectoryName(): Boolean =
    isNotBlank() &&
        this != "." &&
        this != ".." &&
        this != LATEST_TRACE_ID_FILE_NAME &&
        SAFE_TRACE_DIRECTORY_NAME.matches(this)

private val SAFE_TRACE_DIRECTORY_NAME = Regex("[A-Za-z0-9._-]+")
private const val LATEST_TRACE_ID_FILE_NAME = "latest-trace-id.txt"
private const val MAX_TRACE_ARTIFACT_DIRECTORIES = 3
private const val TAG = "VoiceE2EArtifactWriter"
