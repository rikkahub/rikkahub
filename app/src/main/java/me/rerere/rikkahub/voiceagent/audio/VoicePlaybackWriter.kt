package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

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
    data class SinkDrainFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkRetirementFailed(val message: String) : VoicePlaybackDiagnostic
    data class PlaybackSuppressed(val generation: Long) : VoicePlaybackDiagnostic
    data object Released : VoicePlaybackDiagnostic
}

internal class VoicePlaybackWriter(
    scope: CoroutineScope,
    private val createSink: () -> VoicePcm16Sink?,
    private val onDiagnostic: (VoicePlaybackDiagnostic) -> Unit = {},
    private val onPlaybackEvent: (VoicePlaybackEvent) -> Unit = {},
) {
    private val lock = Any()
    private val retirementLock = Any()
    private val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is PlaybackCommand.Play -> playCommand(command)
                is PlaybackCommand.Drain -> drainCommand(command)
            }
        }
    }

    private var activeSessionId: Long? = null
    private var generation = 0L
    private var activeSink: VoicePcm16Sink? = null
    private var released = false
    private var nextPlaybackEpoch = 0L
    private var acceptingPlaybackEpoch: Long? = null
    private val epochWriterGenerations = mutableMapOf<Long, Long>()
    private var retirementInProgress = false
    private var playbackRetirementFailed = false

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
        val enqueued = synchronized(lock) {
            if (released || retirementInProgress || playbackRetirementFailed) {
                null
            } else if (sessionId != null && activeSessionId != sessionId) {
                staleActiveGeneration = generation
                staleActiveSessionId = activeSessionId
                null
            } else {
                val existingEpoch = acceptingPlaybackEpoch
                val playbackEpoch = existingEpoch ?: (++nextPlaybackEpoch).also { epoch ->
                    acceptingPlaybackEpoch = epoch
                    epochWriterGenerations[epoch] = generation
                }
                val command = PlaybackCommand.Play(
                    pcm16 = pcm16,
                    writerGeneration = generation,
                )
                if (!commands.trySend(command).isSuccess) {
                    if (existingEpoch == null) {
                        acceptingPlaybackEpoch = null
                        epochWriterGenerations.remove(playbackEpoch)
                    }
                    null
                } else {
                    if (existingEpoch == null) {
                        onPlaybackEvent(VoicePlaybackEvent.Active(playbackEpoch))
                    }
                    command
                }
            }
        }
        if (enqueued == null) {
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

        onDiagnostic(
            VoicePlaybackDiagnostic.ChunkQueued(
                bytes = pcm16.size,
                generation = enqueued.writerGeneration,
            ),
        )
        return true
    }

    fun markTurnComplete(sessionId: Long?): Boolean {
        return synchronized(lock) {
            if (released || (sessionId != null && activeSessionId != sessionId)) return false
            val epoch = acceptingPlaybackEpoch ?: return true
            acceptingPlaybackEpoch = null
            commands.trySend(
                PlaybackCommand.Drain(
                    writerGeneration = generation,
                    playbackEpoch = epoch,
                ),
            ).isSuccess
        }
    }

    fun activateSession(sessionId: Long) {
        retireCurrentWriterGeneration {
            if (released) return
            activeSessionId = sessionId
        }
    }

    fun invalidateSession() {
        retireCurrentWriterGeneration {
            if (released) return
            activeSessionId = null
        }
    }

    fun suppress() {
        val retiredGeneration = retireCurrentWriterGeneration {
            if (released) return
        } ?: return
        onDiagnostic(VoicePlaybackDiagnostic.PlaybackSuppressed(retiredGeneration + 1))
    }

    fun release() {
        retireCurrentWriterGeneration {
            if (released) return
            released = true
            activeSessionId = null
        }
        commands.close()
        worker.cancel()
        onDiagnostic(VoicePlaybackDiagnostic.Released)
    }

    private fun playCommand(command: PlaybackCommand.Play) {
        if (!isCurrent(command.writerGeneration)) {
            emitStale(command.writerGeneration)
            return
        }

        val sink = getOrCreateSink(command.writerGeneration) ?: return
        if (!isCurrentSink(command.writerGeneration, sink)) {
            emitStale(command.writerGeneration)
            return
        }

        when (val result = VoicePcm16SinkLifecycle.writeFully(sink, command.pcm16)) {
            is VoicePcm16Sink.WriteResult.Written -> {
                if (isCurrentSink(command.writerGeneration, sink)) {
                    onDiagnostic(
                        VoicePlaybackDiagnostic.ChunkWritten(
                            bytes = result.bytes,
                            generation = command.writerGeneration,
                        ),
                    )
                } else {
                    emitStale(command.writerGeneration)
                }
            }
            is VoicePcm16Sink.WriteResult.Failed -> {
                retireWriterGenerationAfterFlush(command.writerGeneration, sink)
                onDiagnostic(VoicePlaybackDiagnostic.SinkWriteFailed(result.message))
            }
            VoicePcm16Sink.WriteResult.Interrupted -> {
                retireWriterGenerationAfterFlush(command.writerGeneration, sink)
                emitStale(command.writerGeneration)
            }
        }
    }

    private fun drainCommand(command: PlaybackCommand.Drain) {
        val sink = synchronized(lock) {
            if (released || generation != command.writerGeneration) return
            onPlaybackEvent(VoicePlaybackEvent.DrainStarted(command.playbackEpoch))
            activeSink
        }
        val result = if (sink == null) {
            VoicePcm16Sink.DrainResult.Drained
        } else {
            VoicePcm16SinkLifecycle.awaitDrained(sink)
        }
        when (result) {
            VoicePcm16Sink.DrainResult.Drained -> {
                synchronized(lock) {
                    val completed = epochWriterGenerations.remove(command.playbackEpoch) == command.writerGeneration
                    if (completed) onPlaybackEvent(VoicePlaybackEvent.Drained(command.playbackEpoch))
                }
            }
            VoicePcm16Sink.DrainResult.Interrupted -> Unit
            is VoicePcm16Sink.DrainResult.Failed -> {
                onDiagnostic(VoicePlaybackDiagnostic.SinkDrainFailed(result.message))
                retireWriterGenerationAfterFlush(command.writerGeneration, sink)
            }
        }
    }

    private fun getOrCreateSink(commandGeneration: Long): VoicePcm16Sink? {
        var staleActiveGeneration: Long? = null
        val currentSink = synchronized(lock) {
            if (released || retirementInProgress || playbackRetirementFailed || generation != commandGeneration) {
                staleActiveGeneration = generation
                null
            } else {
                activeSink
            }
        }
        if (staleActiveGeneration != null) {
            emitStale(commandGeneration)
            return null
        }
        if (currentSink != null) return currentSink

        val newSink = when (
            val outcome = VoicePcm16SinkLifecycle.createStarted(
                createSink = createSink,
                nullSinkMessage = "Playback sink creation failed",
            )
        ) {
            is VoicePcm16SinkLifecycle.StartOutcome.Started -> outcome.sink
            is VoicePcm16SinkLifecycle.StartOutcome.Failed -> {
                retireWriterGenerationAfterFlush(commandGeneration, outcome.sinkRequiringRetirement)
                onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(outcome.message))
                return null
            }
        }

        var staleGeneration: Long? = null
        val selectedSink = synchronized(lock) {
            if (released || retirementInProgress || playbackRetirementFailed || generation != commandGeneration) {
                staleGeneration = generation
                null
            } else {
                activeSink ?: newSink.also { activeSink = it }
            }
        }

        if (selectedSink == null) {
            retireDetachedSink(newSink)
            onDiagnostic(
                VoicePlaybackDiagnostic.StaleChunkRejected(
                    generation = commandGeneration,
                    activeGeneration = staleGeneration ?: currentGeneration(),
                ),
            )
            return null
        }

        if (selectedSink !== newSink) retireDetachedSink(newSink)
        return selectedSink
    }

    private fun retireWriterGenerationAfterFlush(commandGeneration: Long, sink: VoicePcm16Sink?) {
        synchronized(retirementLock) {
            val retirement = synchronized(lock) {
                if (released || generation != commandGeneration) return
                beginRetirementLocked()
            }
            finishRetirement(retirement, sink ?: retirement.sink)
        }
    }

    private inline fun retireCurrentWriterGeneration(updateState: () -> Unit): Long? {
        synchronized(retirementLock) {
            val retirement = synchronized(lock) {
                updateState()
                beginRetirementLocked()
            }
            finishRetirement(retirement, retirement.sink)
            return retirement.writerGeneration
        }
    }

    private fun beginRetirementLocked(): Retirement {
        val writerGeneration = generation
        retirementInProgress = true
        generation += 1
        acceptingPlaybackEpoch = null
        val sink = activeSink
        activeSink = null
        return Retirement(
            writerGeneration = writerGeneration,
            sink = sink,
            playbackEpochs = epochWriterGenerations
                .filterValues { it == writerGeneration }
                .keys
                .sorted(),
        )
    }

    private fun finishRetirement(retirement: Retirement, sink: VoicePcm16Sink?) {
        val safelyRetired = VoicePcm16SinkLifecycle.retireForSafety(sink)
        if (!safelyRetired) {
            synchronized(lock) {
                playbackRetirementFailed = true
            }
            onDiagnostic(
                VoicePlaybackDiagnostic.SinkRetirementFailed(
                    "Unable to flush or release playback sink",
                ),
            )
            return
        }

        VoicePcm16SinkLifecycle.stopAndReleaseSafely(sink)

        synchronized(lock) {
            retirement.playbackEpochs.forEach { epoch ->
                if (epochWriterGenerations.remove(epoch) == retirement.writerGeneration) {
                    onPlaybackEvent(VoicePlaybackEvent.Drained(epoch))
                }
            }
            retirementInProgress = false
        }
    }

    private fun retireDetachedSink(sink: VoicePcm16Sink) {
        if (VoicePcm16SinkLifecycle.retireForSafety(sink)) {
            VoicePcm16SinkLifecycle.stopAndReleaseSafely(sink)
        } else {
            synchronized(lock) {
                retirementInProgress = true
                playbackRetirementFailed = true
            }
            onDiagnostic(
                VoicePlaybackDiagnostic.SinkRetirementFailed(
                    "Unable to flush or release playback sink",
                ),
            )
        }
    }

    private fun isCurrent(commandGeneration: Long): Boolean = synchronized(lock) {
        !released && !retirementInProgress && !playbackRetirementFailed && generation == commandGeneration
    }

    private fun isCurrentSink(commandGeneration: Long, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        !released && !retirementInProgress && !playbackRetirementFailed &&
            generation == commandGeneration && activeSink === sink
    }

    private fun emitStale(commandGeneration: Long) {
        onDiagnostic(
            VoicePlaybackDiagnostic.StaleChunkRejected(
                generation = commandGeneration,
                activeGeneration = currentGeneration(),
            ),
        )
    }

    private fun currentGeneration(): Long = synchronized(lock) { generation }

    private sealed interface PlaybackCommand {
        data class Play(
            val pcm16: ByteArray,
            val writerGeneration: Long,
        ) : PlaybackCommand

        data class Drain(
            val writerGeneration: Long,
            val playbackEpoch: Long,
        ) : PlaybackCommand
    }

    private data class Retirement(
        val writerGeneration: Long,
        val sink: VoicePcm16Sink?,
        val playbackEpochs: List<Long>,
    )
}
