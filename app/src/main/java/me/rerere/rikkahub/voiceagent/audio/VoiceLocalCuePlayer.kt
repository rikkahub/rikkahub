package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

internal sealed interface VoiceLocalCueDiagnostic {
    data class ChunkQueued(val bytes: Int, val generation: Long) : VoiceLocalCueDiagnostic
    data class ChunkWritten(val bytes: Int, val generation: Long) : VoiceLocalCueDiagnostic
    data class StaleCueRejected(
        val generation: Long,
        val activeGeneration: Long,
        val rejectedToken: Long? = null,
    ) : VoiceLocalCueDiagnostic
    data class MalformedCue(val message: String) : VoiceLocalCueDiagnostic
    data class SinkStartFailed(val message: String) : VoiceLocalCueDiagnostic
    data class SinkWriteFailed(val message: String) : VoiceLocalCueDiagnostic
    data object Released : VoiceLocalCueDiagnostic
}

internal class VoiceLocalCuePlayer(
    scope: CoroutineScope,
    private val createSink: () -> VoicePcm16Sink?,
    private val onDiagnostic: (VoiceLocalCueDiagnostic) -> Unit = {},
) {
    private val lock = Any()
    private val commands = Channel<PlaybackCommand>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is PlaybackCommand.Play -> playCommand(command)
            }
        }
    }

    private var generation = 0L
    private var highestInvalidatedToken: Long? = null
    private var activeSink: VoicePcm16Sink? = null
    private var retiredSink: VoicePcm16Sink? = null
    private var released = false

    fun playBase64(base64Pcm16: String, token: Long?): Boolean {
        val pcm16 = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (e: IllegalArgumentException) {
            onDiagnostic(VoiceLocalCueDiagnostic.MalformedCue(e.message ?: "Malformed local cue chunk"))
            return false
        }
        if (pcm16.isEmpty()) {
            onDiagnostic(VoiceLocalCueDiagnostic.MalformedCue("Empty local cue chunk"))
            return false
        }

        var rejectedGeneration: Long? = null
        val command = synchronized(lock) {
            if (released) {
                null
            } else if (isTokenInvalidatedLocked(token)) {
                rejectedGeneration = generation
                null
            } else {
                PlaybackCommand.Play(
                    pcm16 = pcm16,
                    generation = generation,
                    token = token,
                )
            }
        }

        if (command == null) {
            rejectedGeneration?.let { activeGeneration ->
                onDiagnostic(
                    VoiceLocalCueDiagnostic.StaleCueRejected(
                        generation = activeGeneration,
                        activeGeneration = activeGeneration,
                        rejectedToken = token,
                    ),
                )
            }
            return false
        }

        if (!commands.trySend(command).isSuccess) {
            return false
        }

        onDiagnostic(
            VoiceLocalCueDiagnostic.ChunkQueued(
                bytes = pcm16.size,
                generation = command.generation,
            ),
        )
        return true
    }

    fun invalidate(token: Long? = null) {
        val result = synchronized(lock) {
            if (released) {
                InvalidateResult()
            } else {
                generation += 1
                token?.let { invalidatedToken ->
                    highestInvalidatedToken = maxOf(
                        highestInvalidatedToken ?: invalidatedToken,
                        invalidatedToken,
                    )
                }
                val sinkToFlush = activeSink
                val sinkToRelease = if (sinkToFlush != null && retiredSink !== sinkToFlush) {
                    retiredSink
                } else {
                    null
                }
                if (sinkToFlush != null) {
                    activeSink = null
                    retiredSink = sinkToFlush
                }
                InvalidateResult(sinkToFlush = sinkToFlush, sinkToRelease = sinkToRelease)
            }
        }
        result.sinkToRelease?.stopAndRelease()
        result.sinkToFlush?.pauseAndFlush()
    }

    fun release() {
        val retired = synchronized(lock) {
            if (released) {
                return
            }
            released = true
            generation += 1
            RetiredSinks(active = activeSink, retired = retiredSink).also {
                activeSink = null
                retiredSink = null
            }
        }
        retired.stopAndRelease()
        commands.close()
        worker.cancel()
        onDiagnostic(VoiceLocalCueDiagnostic.Released)
    }

    private fun playCommand(command: PlaybackCommand.Play) {
        if (!isCurrent(command)) {
            emitStale(command.generation)
            return
        }

        val sink = getOrCreateSink(command) ?: return
        if (!isCurrentSink(command, sink)) {
            emitStale(command.generation)
            return
        }

        val result = try {
            sink.writeFully(command.pcm16)
        } catch (e: Exception) {
            if (clearSink(sink)) {
                sink.stopAndRelease()
            }
            onDiagnostic(VoiceLocalCueDiagnostic.SinkWriteFailed(e.message ?: e.javaClass.simpleName))
            return
        }

        when (result) {
            is VoicePcm16Sink.WriteResult.Written -> {
                if (isCurrentSink(command, sink)) {
                    onDiagnostic(
                        VoiceLocalCueDiagnostic.ChunkWritten(
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
                onDiagnostic(VoiceLocalCueDiagnostic.SinkWriteFailed(result.message))
            }
            VoicePcm16Sink.WriteResult.Interrupted -> {
                if (clearSink(sink)) {
                    sink.stopAndRelease()
                }
                emitStale(command.generation)
            }
        }
    }

    private fun getOrCreateSink(command: PlaybackCommand.Play): VoicePcm16Sink? {
        var staleGeneration: Long? = null
        val currentSink = synchronized(lock) {
            if (!isCurrentLocked(command)) {
                staleGeneration = generation
                null
            } else {
                activeSink
            }
        }

        if (staleGeneration != null) {
            onDiagnostic(
                VoiceLocalCueDiagnostic.StaleCueRejected(
                    generation = command.generation,
                    activeGeneration = staleGeneration,
                    rejectedToken = command.token,
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
            onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
            return null
        } ?: run {
            onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed("Local cue sink creation failed"))
            return null
        }

        val startResult = try {
            newSink.start()
        } catch (e: Exception) {
            newSink.stopAndRelease()
            onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
            return null
        }

        when (startResult) {
            VoicePcm16Sink.StartResult.Started -> Unit
            is VoicePcm16Sink.StartResult.Failed -> {
                newSink.stopAndRelease()
                onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(startResult.message))
                return null
            }
        }

        var selectedStaleGeneration: Long? = null
        val selectedSink = synchronized(lock) {
            if (!isCurrentLocked(command)) {
                selectedStaleGeneration = generation
                null
            } else {
                activeSink ?: newSink.also { activeSink = it }
            }
        }

        if (selectedSink == null) {
            newSink.stopAndRelease()
            onDiagnostic(
                VoiceLocalCueDiagnostic.StaleCueRejected(
                    generation = command.generation,
                    activeGeneration = selectedStaleGeneration ?: currentGeneration(),
                    rejectedToken = command.token,
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
            !isTokenInvalidatedLocked(command.token)
    }

    private fun isTokenInvalidatedLocked(token: Long?): Boolean =
        token != null && highestInvalidatedToken?.let { token <= it } == true

    private fun isCurrentSink(command: PlaybackCommand.Play, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        isCurrentLocked(command) && activeSink === sink
    }

    private fun clearSink(sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        var cleared = false
        if (activeSink === sink) {
            activeSink = null
            cleared = true
        }
        if (retiredSink === sink) {
            retiredSink = null
            cleared = true
        }
        return cleared
    }

    private fun emitStale(commandGeneration: Long) {
        onDiagnostic(
            VoiceLocalCueDiagnostic.StaleCueRejected(
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
            val token: Long?,
        ) : PlaybackCommand
    }

    private data class InvalidateResult(
        val sinkToFlush: VoicePcm16Sink? = null,
        val sinkToRelease: VoicePcm16Sink? = null,
    )

    private data class RetiredSinks(
        val active: VoicePcm16Sink?,
        val retired: VoicePcm16Sink?,
    ) {
        fun stopAndRelease() {
            active?.stopAndRelease()
            if (retired !== active) {
                retired?.stopAndRelease()
            }
        }
    }
}
