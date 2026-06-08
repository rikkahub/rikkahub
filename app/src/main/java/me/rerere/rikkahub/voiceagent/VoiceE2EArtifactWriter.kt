package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

internal val VoiceE2EArtifactNames = setOf(
    "input-transcript.txt",
    "output-transcript.txt",
    "hermes-call.txt",
    "hermes-answer.txt",
)

class VoiceE2EArtifactWriter private constructor(
    enabled: Boolean,
    rootDirectory: File,
    scope: CoroutineScope?,
) {
    private val commands = if (enabled) Channel<WriteCommand>(Channel.UNLIMITED) else null
    private val directory = File(rootDirectory, "voice-e2e")

    init {
        val queue = commands
        if (queue != null && scope != null) {
            scope.launch(Dispatchers.IO) {
                for (command in queue) {
                    when (command) {
                        is WriteCommand.Write -> writeArtifact(command.name, command.content)
                        is WriteCommand.Drain -> command.completed.complete(Unit)
                    }
                }
            }
        }
    }

    operator fun invoke(name: String, content: String) = write(name, content)

    fun write(name: String, content: String) {
        val queue = commands ?: return
        if (name !in VoiceE2EArtifactNames) {
            VoiceAgentLog.w(TAG, "ignored unexpected artifact name=$name")
            return
        }
        if (queue.trySend(WriteCommand.Write(name = name, content = content)).isFailure) {
            VoiceAgentLog.w(TAG, "artifact write queue rejected name=$name")
        }
    }

    suspend fun drain() {
        val queue = commands ?: return
        val completed = CompletableDeferred<Unit>()
        if (queue.trySend(WriteCommand.Drain(completed)).isSuccess) {
            completed.await()
        }
    }

    private fun writeArtifact(name: String, content: String) {
        runCatching {
            directory.mkdirs()
            File(directory, name).writeText(content)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            VoiceAgentLog.w(TAG, "artifact write failed name=$name message=$message")
        }
    }

    private sealed interface WriteCommand {
        data class Write(val name: String, val content: String) : WriteCommand
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
