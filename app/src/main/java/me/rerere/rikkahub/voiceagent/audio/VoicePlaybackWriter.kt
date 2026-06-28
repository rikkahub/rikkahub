package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

internal interface VoicePcm16Sink {
    fun start(source: VoicePlaybackSource): StartResult
    fun writeFully(pcm16: ByteArray, source: VoicePlaybackSource): WriteResult
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
    data class ChunkQueued(
        val bytes: Int,
        val generation: Long,
        val source: VoicePlaybackSource = VoicePlaybackSource.Assistant,
    ) : VoicePlaybackDiagnostic
    data class ChunkWritten(
        val bytes: Int,
        val generation: Long,
        val source: VoicePlaybackSource = VoicePlaybackSource.Assistant,
    ) : VoicePlaybackDiagnostic
    data class StaleChunkRejected(
        val generation: Long,
        val activeGeneration: Long,
        val rejectedSessionId: Long? = null,
        val activeSessionId: Long? = null,
        val source: VoicePlaybackSource = VoicePlaybackSource.Assistant,
    ) : VoicePlaybackDiagnostic
    data class MalformedChunk(
        val message: String,
        val source: VoicePlaybackSource = VoicePlaybackSource.Assistant,
    ) : VoicePlaybackDiagnostic
    data class SinkStartFailed(
        val message: String,
        val source: VoicePlaybackSource = VoicePlaybackSource.Assistant,
    ) : VoicePlaybackDiagnostic
    data class SinkWriteFailed(
        val message: String,
        val source: VoicePlaybackSource = VoicePlaybackSource.Assistant,
    ) : VoicePlaybackDiagnostic
    data class PlaybackSuppressed(val generation: Long) : VoicePlaybackDiagnostic
    data object Released : VoicePlaybackDiagnostic
}

internal enum class VoicePlaybackSource {
    Assistant,
    LocalCue,
}

internal class VoicePlaybackWriter(
    scope: CoroutineScope,
    private val createSink: (VoicePlaybackSource) -> VoicePcm16Sink?,
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
    private var localCueGeneration = 0L
    private var activeSink: VoicePcm16Sink? = null
    private var released = false

    fun playBase64(base64Pcm16: String, sessionId: Long?): Boolean {
        return playBase64(
            base64Pcm16 = base64Pcm16,
            sessionId = sessionId,
            source = VoicePlaybackSource.Assistant,
        )
    }

    fun playBase64(base64Pcm16: String, sessionId: Long?, source: VoicePlaybackSource): Boolean {
        val pcm16 = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (e: IllegalArgumentException) {
            onDiagnostic(
                VoicePlaybackDiagnostic.MalformedChunk(
                    message = e.message ?: "Malformed playback chunk",
                    source = source,
                ),
            )
            return false
        }
        if (pcm16.isEmpty()) {
            onDiagnostic(VoicePlaybackDiagnostic.MalformedChunk(message = "Empty playback chunk", source = source))
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
                PlaybackCommand.Play(
                    pcm16 = pcm16,
                    generation = generation,
                    localCueGeneration = localCueGeneration,
                    source = source,
                )
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
                        source = source,
                    ),
                )
            }
            return false
        }

        if (!commands.trySend(command).isSuccess) {
            return false
        }

        onDiagnostic(
            VoicePlaybackDiagnostic.ChunkQueued(
                bytes = pcm16.size,
                generation = command.generation,
                source = source,
            ),
        )
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

    fun invalidateLocalCues() {
        synchronized(lock) {
            if (released) {
                return
            }
            localCueGeneration += 1
        }
    }

    fun release() {
        val sink = synchronized(lock) {
            if (released) {
                return
            }
            released = true
            generation += 1
            localCueGeneration += 1
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
        if (!isCurrent(command)) {
            emitStale(command.generation, command.source)
            return
        }

        val sink = getOrCreateSink(command) ?: return
        if (!isCurrentSink(command, sink)) {
            emitStale(command.generation, command.source)
            return
        }

        when (val result = sink.writeFully(command.pcm16, command.source)) {
            is VoicePcm16Sink.WriteResult.Written -> {
                if (isCurrentSink(command, sink)) {
                    onDiagnostic(
                        VoicePlaybackDiagnostic.ChunkWritten(
                            bytes = result.bytes,
                            generation = command.generation,
                            source = command.source,
                        ),
                    )
                } else {
                    emitStale(command.generation, command.source)
                }
            }
            is VoicePcm16Sink.WriteResult.Failed -> {
                if (clearSink(sink)) {
                    sink.stopAndRelease()
                }
                onDiagnostic(
                    VoicePlaybackDiagnostic.SinkWriteFailed(
                        message = result.message,
                        source = command.source,
                    ),
                )
            }
            VoicePcm16Sink.WriteResult.Interrupted -> {
                clearSink(sink)
                emitStale(command.generation, command.source)
            }
        }
    }

    private fun getOrCreateSink(command: PlaybackCommand.Play): VoicePcm16Sink? {
        var staleActiveGeneration: Long? = null
        val currentSink = synchronized(lock) {
            if (!isCurrentLocked(command)) {
                staleActiveGeneration = generation
                null
            } else {
                activeSink
            }
        }
        if (staleActiveGeneration != null) {
            onDiagnostic(
                VoicePlaybackDiagnostic.StaleChunkRejected(
                    generation = command.generation,
                    activeGeneration = staleActiveGeneration,
                    source = command.source,
                ),
            )
            return null
        }
        if (currentSink != null) {
            return currentSink
        }

        val newSink = try {
            createSink(command.source)
        } catch (e: Exception) {
            onDiagnostic(
                VoicePlaybackDiagnostic.SinkStartFailed(
                    message = e.message ?: e.javaClass.simpleName,
                    source = command.source,
                ),
            )
            return null
        } ?: run {
            onDiagnostic(
                VoicePlaybackDiagnostic.SinkStartFailed(
                    message = "Playback sink creation failed",
                    source = command.source,
                ),
            )
            return null
        }

        val startResult = try {
            newSink.start(command.source)
        } catch (e: Exception) {
            newSink.stopAndRelease()
            onDiagnostic(
                VoicePlaybackDiagnostic.SinkStartFailed(
                    message = e.message ?: e.javaClass.simpleName,
                    source = command.source,
                ),
            )
            return null
        }

        when (startResult) {
            VoicePcm16Sink.StartResult.Started -> Unit
            is VoicePcm16Sink.StartResult.Failed -> {
                newSink.stopAndRelease()
                onDiagnostic(
                    VoicePlaybackDiagnostic.SinkStartFailed(
                        message = startResult.message,
                        source = command.source,
                    ),
                )
                return null
            }
        }

        var staleGeneration: Long? = null
        val selectedSink = synchronized(lock) {
            if (!isCurrentLocked(command)) {
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
                    generation = command.generation,
                    activeGeneration = staleGeneration ?: currentGeneration(),
                    source = command.source,
                ),
            )
            return null
        }

        if (selectedSink !== newSink) {
            newSink.stopAndRelease()
        }
        return selectedSink
    }

    private fun isCurrent(command: PlaybackCommand.Play): Boolean = synchronized(lock) {
        isCurrentLocked(command)
    }

    private fun isCurrentLocked(command: PlaybackCommand.Play): Boolean {
        return !released &&
            generation == command.generation &&
            (
                command.source != VoicePlaybackSource.LocalCue ||
                    localCueGeneration == command.localCueGeneration
                )
    }

    private fun isCurrentSink(command: PlaybackCommand.Play, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        isCurrentLocked(command) && activeSink === sink
    }

    private fun clearSink(sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        if (activeSink === sink) {
            activeSink = null
            true
        } else {
            false
        }
    }

    private fun emitStale(commandGeneration: Long, source: VoicePlaybackSource) {
        onDiagnostic(
            VoicePlaybackDiagnostic.StaleChunkRejected(
                generation = commandGeneration,
                activeGeneration = currentGeneration(),
                source = source,
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
            val localCueGeneration: Long,
            val source: VoicePlaybackSource,
        ) : PlaybackCommand
    }

    private data class SuppressResult(
        val sink: VoicePcm16Sink?,
        val generation: Long,
    )
}
