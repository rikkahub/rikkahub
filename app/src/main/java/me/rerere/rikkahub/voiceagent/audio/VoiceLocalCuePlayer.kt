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
        val rejectedCueToken: Long? = null,
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

    fun playBase64(base64Pcm16: String, cueToken: Long?): Boolean {
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
            } else if (isCueTokenInvalidatedLocked(cueToken)) {
                rejectedGeneration = generation
                null
            } else {
                PlaybackCommand.Play(
                    pcm16 = pcm16,
                    generation = generation,
                    cueToken = cueToken,
                )
            }
        }

        if (command == null) {
            rejectedGeneration?.let { activeGeneration ->
                onDiagnostic(
                    VoiceLocalCueDiagnostic.StaleCueRejected(
                        generation = activeGeneration,
                        activeGeneration = activeGeneration,
                        rejectedCueToken = cueToken,
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

    fun invalidate(cueToken: Long? = null) {
        val result = synchronized(lock) {
            if (released) {
                InvalidateResult()
            } else {
                generation += 1
                cueToken?.let { invalidatedToken ->
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
        VoicePcm16SinkLifecycle.stopAndRelease(result.sinkToRelease)
        VoicePcm16SinkLifecycle.pauseAndFlush(result.sinkToFlush)
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
        VoicePcm16SinkLifecycle.stopAndReleaseDistinct(retired.active, retired.retired)
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

        when (val result = VoicePcm16SinkLifecycle.writeFully(sink, command.pcm16)) {
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
                    VoicePcm16SinkLifecycle.stopAndRelease(sink)
                }
                onDiagnostic(VoiceLocalCueDiagnostic.SinkWriteFailed(result.message))
            }
            VoicePcm16Sink.WriteResult.Interrupted -> {
                if (clearSink(sink)) {
                    VoicePcm16SinkLifecycle.stopAndRelease(sink)
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
                    rejectedCueToken = command.cueToken,
                ),
            )
            return null
        }
        if (currentSink != null) {
            return currentSink
        }

        val newSink = when (
            val outcome = VoicePcm16SinkLifecycle.createStarted(
                createSink = createSink,
                nullSinkMessage = "Local cue sink creation failed",
            )
        ) {
            is VoicePcm16SinkLifecycle.StartOutcome.Started -> outcome.sink
            is VoicePcm16SinkLifecycle.StartOutcome.Failed -> {
                onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(outcome.message))
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
            VoicePcm16SinkLifecycle.stopAndRelease(newSink)
            onDiagnostic(
                VoiceLocalCueDiagnostic.StaleCueRejected(
                    generation = command.generation,
                    activeGeneration = selectedStaleGeneration ?: currentGeneration(),
                    rejectedCueToken = command.cueToken,
                ),
            )
            return null
        }

        if (selectedSink !== newSink) {
            VoicePcm16SinkLifecycle.stopAndRelease(newSink)
        }
        return selectedSink
    }

    private fun isCurrent(command: PlaybackCommand.Play): Boolean = synchronized(lock) {
        isCurrentLocked(command)
    }

    private fun isCurrentLocked(command: PlaybackCommand.Play): Boolean {
        return !released &&
            generation == command.generation &&
            !isCueTokenInvalidatedLocked(command.cueToken)
    }

    private fun isCueTokenInvalidatedLocked(cueToken: Long?): Boolean =
        cueToken != null && highestInvalidatedToken?.let { cueToken <= it } == true

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
            val cueToken: Long?,
        ) : PlaybackCommand
    }

    private data class InvalidateResult(
        val sinkToFlush: VoicePcm16Sink? = null,
        val sinkToRelease: VoicePcm16Sink? = null,
    )

    private data class RetiredSinks(
        val active: VoicePcm16Sink?,
        val retired: VoicePcm16Sink?,
    )
}
