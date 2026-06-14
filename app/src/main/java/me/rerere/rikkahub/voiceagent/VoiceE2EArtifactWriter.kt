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
    scope: CoroutineScope?,
) {
    private val commands = if (enabled) {
        Channel<WriteCommand>(capacity = Channel.UNLIMITED)
    } else {
        null
    }
    private val directory = File(rootDirectory, "voice-e2e")
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
                        WriteCommand.ClearAppendOnlyArtifacts -> clearAppendOnlyArtifacts()
                        WriteCommand.Flush -> flushPendingWrites()
                        is WriteCommand.Drain -> {
                            flushPendingWrites()
                            command.completed.complete(Unit)
                        }
                    }
                }
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

    private fun writeArtifact(artifact: VoiceE2EArtifact, content: String, append: Boolean) {
        runCatching {
            directory.mkdirs()
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
        object ClearAppendOnlyArtifacts : WriteCommand
        object Flush : WriteCommand
        data class Drain(val completed: CompletableDeferred<Unit>) : WriteCommand
    }

    companion object {
        fun disabled(): VoiceE2EArtifactWriter = VoiceE2EArtifactWriter(
            enabled = false,
            rootDirectory = File(""),
            scope = null,
        )

        fun create(
            enabled: Boolean,
            rootDirectory: File,
            scope: CoroutineScope,
        ): VoiceE2EArtifactWriter = VoiceE2EArtifactWriter(
            enabled = enabled,
            rootDirectory = rootDirectory,
            scope = scope,
        )
    }
}

private fun String.containsLineBreak(): Boolean = contains('\n') || contains('\r')

private const val TAG = "VoiceE2EArtifactWriter"
