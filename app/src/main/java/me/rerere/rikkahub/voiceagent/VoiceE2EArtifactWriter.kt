package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

enum class VoiceE2EArtifact(val fileName: String) {
    InputTranscript("input-transcript.txt"),
    OutputTranscript("output-transcript.txt"),
    HermesCall("hermes-call.txt"),
    HermesAnswer("hermes-answer.txt"),
}

class VoiceE2EArtifactWriter private constructor(
    enabled: Boolean,
    rootDirectory: File,
    scope: CoroutineScope?,
) {
    private val commands = if (enabled) {
        Channel<WriteCommand>(capacity = 1)
    } else {
        null
    }
    private val directory = File(rootDirectory, "voice-e2e")
    private val pendingLock = Any()
    private val pendingWrites = LinkedHashMap<VoiceE2EArtifact, String>()
    private var flushQueued = false

    init {
        val queue = commands
        if (queue != null && scope != null) {
            scope.launch(Dispatchers.IO) {
                for (command in queue) {
                    when (command) {
                        WriteCommand.Flush -> flushPendingWrites()
                        is WriteCommand.Drain -> {
                            flushPendingWrites()
                            command.completed.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    operator fun invoke(artifact: VoiceE2EArtifact, content: String) = write(artifact, content)

    fun write(artifact: VoiceE2EArtifact, content: String) {
        val queue = commands ?: return
        var shouldQueueFlush = false
        synchronized(pendingLock) {
            pendingWrites[artifact] = content
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

    private fun writeArtifact(artifact: VoiceE2EArtifact, content: String) {
        runCatching {
            directory.mkdirs()
            File(directory, artifact.fileName).writeText(content)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            VoiceAgentLog.w(TAG, "artifact write failed name=${artifact.fileName} message=$message")
        }
    }

    private fun flushPendingWrites() {
        while (true) {
            val snapshot = synchronized(pendingLock) {
                if (pendingWrites.isEmpty()) {
                    flushQueued = false
                    return
                }
                LinkedHashMap(pendingWrites).also {
                    pendingWrites.clear()
                }
            }
            snapshot.forEach { (name, content) ->
                writeArtifact(name, content)
            }
        }
    }

    private sealed interface WriteCommand {
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

private const val TAG = "VoiceE2EArtifactWriter"
