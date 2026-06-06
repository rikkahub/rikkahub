package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

internal interface VoicePcm16Sink {
    fun start(): StartResult
    fun writeFully(pcm16: ByteArray): WriteResult
    fun pauseAndFlush()
    fun stopAndRelease()

    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val message: String) : StartResult
    }

    sealed interface WriteResult {
        data class Written(val bytes: Int) : WriteResult
        data class Failed(val message: String) : WriteResult
        data object Interrupted : WriteResult
    }
}

internal sealed interface VoicePlaybackDiagnostic {
    data class ChunkQueued(val bytes: Int, val generation: Long) : VoicePlaybackDiagnostic
    data class ChunkWritten(val bytes: Int, val generation: Long) : VoicePlaybackDiagnostic
    data class StaleChunkRejected(
        val generation: Long,
        val activeGeneration: Long,
        val rejectedSessionId: Long? = null,
        val activeSessionId: Long? = null,
    ) : VoicePlaybackDiagnostic
    data class MalformedChunk(val message: String) : VoicePlaybackDiagnostic
    data class SinkStartFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkWriteFailed(val message: String) : VoicePlaybackDiagnostic
    data class PlaybackSuppressed(val generation: Long) : VoicePlaybackDiagnostic
    data object Released : VoicePlaybackDiagnostic
}

internal class VoicePlaybackWriter(
    scope: CoroutineScope,
    private val createSink: () -> VoicePcm16Sink?,
    private val onDiagnostic: (VoicePlaybackDiagnostic) -> Unit = {},
) {
    private val lock = Any()
    private val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is PlaybackCommand.Play -> playCommand(command)
            }
        }
    }

    private var activeSessionId: Long? = null
    private var generation = 0L
    private var activeSink: VoicePcm16Sink? = null
    private var released = false

    fun playBase64(base64Pcm16: String, sessionId: Long?): Boolean {
        val pcm16 = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (e: IllegalArgumentException) {
            onDiagnostic(VoicePlaybackDiagnostic.MalformedChunk(e.message ?: "Malformed playback chunk"))
            return false
        }
        if (pcm16.isEmpty()) {
            onDiagnostic(VoicePlaybackDiagnostic.MalformedChunk("Empty playback chunk"))
            return false
        }

        var staleActiveGeneration: Long? = null
        var staleActiveSessionId: Long? = null
        val command = synchronized(lock) {
            if (released) {
                null
            } else if (sessionId != null && activeSessionId != sessionId) {
                staleActiveGeneration = generation
                staleActiveSessionId = activeSessionId
                null
            } else {
                PlaybackCommand.Play(pcm16 = pcm16, generation = generation)
            }
        }
        if (command == null) {
            staleActiveGeneration?.let { activeGeneration ->
                onDiagnostic(
                    VoicePlaybackDiagnostic.StaleChunkRejected(
                        generation = activeGeneration,
                        activeGeneration = activeGeneration,
                        rejectedSessionId = sessionId,
                        activeSessionId = staleActiveSessionId,
                    ),
                )
            }
            return false
        }

        if (!commands.trySend(command).isSuccess) {
            return false
        }

        onDiagnostic(VoicePlaybackDiagnostic.ChunkQueued(bytes = pcm16.size, generation = command.generation))
        return true
    }

    fun activateSession(sessionId: Long) {
        val sink = synchronized(lock) {
            if (released) {
                return
            }
            activeSessionId = sessionId
            generation += 1
            val sink = activeSink
            activeSink = null
            sink
        }
        sink?.stopAndRelease()
    }

    fun invalidateSession() {
        val sink = synchronized(lock) {
            if (released) {
                return
            }
            activeSessionId = null
            generation += 1
            val sink = activeSink
            activeSink = null
            sink
        }
        sink?.stopAndRelease()
    }

    fun suppress() {
        val result = synchronized(lock) {
            if (released) {
                return
            }
            generation += 1
            val sink = activeSink
            activeSink = null
            SuppressResult(sink = sink, generation = generation)
        }
        result.sink?.stopAndRelease()
        onDiagnostic(VoicePlaybackDiagnostic.PlaybackSuppressed(result.generation))
    }

    fun release() {
        val sink = synchronized(lock) {
            if (released) {
                return
            }
            released = true
            generation += 1
            activeSessionId = null
            val sink = activeSink
            activeSink = null
            sink
        }
        sink?.stopAndRelease()
        commands.close()
        worker.cancel()
        onDiagnostic(VoicePlaybackDiagnostic.Released)
    }

    private fun playCommand(command: PlaybackCommand.Play) {
        if (!isCurrent(command.generation)) {
            emitStale(command.generation)
            return
        }

        val sink = getOrCreateSink(command.generation) ?: return
        if (!isCurrentSink(command.generation, sink)) {
            emitStale(command.generation)
            return
        }

        when (val result = sink.writeFully(command.pcm16)) {
            is VoicePcm16Sink.WriteResult.Written -> {
                if (isCurrentSink(command.generation, sink)) {
                    onDiagnostic(
                        VoicePlaybackDiagnostic.ChunkWritten(
                            bytes = result.bytes,
                            generation = command.generation,
                        ),
                    )
                } else {
                    emitStale(command.generation)
                }
            }
            is VoicePcm16Sink.WriteResult.Failed -> {
                if (clearSink(sink)) {
                    sink.stopAndRelease()
                }
                onDiagnostic(VoicePlaybackDiagnostic.SinkWriteFailed(result.message))
            }
            VoicePcm16Sink.WriteResult.Interrupted -> {
                clearSink(sink)
                emitStale(command.generation)
            }
        }
    }

    private fun getOrCreateSink(commandGeneration: Long): VoicePcm16Sink? {
        var staleActiveGeneration: Long? = null
        val currentSink = synchronized(lock) {
            if (released || generation != commandGeneration) {
                staleActiveGeneration = generation
                null
            } else {
                activeSink
            }
        }
        if (staleActiveGeneration != null) {
            onDiagnostic(
                VoicePlaybackDiagnostic.StaleChunkRejected(
                    generation = commandGeneration,
                    activeGeneration = staleActiveGeneration,
                ),
            )
            return null
        }
        if (currentSink != null) {
            return currentSink
        }

        val newSink = try {
            createSink()
        } catch (e: Exception) {
            onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
            return null
        } ?: run {
            onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed("Playback sink creation failed"))
            return null
        }

        val startResult = try {
            newSink.start()
        } catch (e: Exception) {
            newSink.stopAndRelease()
            onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
            return null
        }

        when (startResult) {
            VoicePcm16Sink.StartResult.Started -> Unit
            is VoicePcm16Sink.StartResult.Failed -> {
                newSink.stopAndRelease()
                onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(startResult.message))
                return null
            }
        }

        var staleGeneration: Long? = null
        val selectedSink = synchronized(lock) {
            if (released || generation != commandGeneration) {
                staleGeneration = generation
                null
            } else {
                activeSink ?: newSink.also { activeSink = it }
            }
        }

        if (selectedSink == null) {
            newSink.stopAndRelease()
            onDiagnostic(
                VoicePlaybackDiagnostic.StaleChunkRejected(
                    generation = commandGeneration,
                    activeGeneration = staleGeneration ?: currentGeneration(),
                ),
            )
            return null
        }

        if (selectedSink !== newSink) {
            newSink.stopAndRelease()
        }
        return selectedSink
    }

    private fun isCurrent(commandGeneration: Long): Boolean = synchronized(lock) {
        !released && generation == commandGeneration
    }

    private fun isCurrentSink(commandGeneration: Long, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        !released && generation == commandGeneration && activeSink === sink
    }

    private fun clearSink(sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        if (activeSink === sink) {
            activeSink = null
            true
        } else {
            false
        }
    }

    private fun emitStale(commandGeneration: Long) {
        onDiagnostic(
            VoicePlaybackDiagnostic.StaleChunkRejected(
                generation = commandGeneration,
                activeGeneration = currentGeneration(),
            ),
        )
    }

    private fun currentGeneration(): Long = synchronized(lock) {
        generation
    }

    private sealed interface PlaybackCommand {
        data class Play(
            val pcm16: ByteArray,
            val generation: Long,
        ) : PlaybackCommand
    }

    private data class SuppressResult(
        val sink: VoicePcm16Sink?,
        val generation: Long,
    )
}
