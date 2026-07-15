package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

internal sealed interface VoicePlaybackDiagnostic {
    data class ChunkQueued(val bytes: Int, val writerGeneration: WriterGeneration) : VoicePlaybackDiagnostic
    data class ChunkWritten(val bytes: Int, val writerGeneration: WriterGeneration) : VoicePlaybackDiagnostic
    data class StaleChunkRejected(
        val writerGeneration: WriterGeneration,
        val activeWriterGeneration: WriterGeneration,
        val rejectedSessionId: Long? = null,
        val activeSessionId: Long? = null,
    ) : VoicePlaybackDiagnostic
    data class MalformedChunk(val message: String) : VoicePlaybackDiagnostic
    data class SinkStartFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkWriteFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkDrainFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkRetirementFailed(val message: String) : VoicePlaybackDiagnostic
    data class PlaybackEventHandlerFailed(
        val event: VoicePlaybackEvent,
        val message: String,
    ) : VoicePlaybackDiagnostic
    data class PlaybackSuppressed(val writerGeneration: WriterGeneration) : VoicePlaybackDiagnostic
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
    private val playbackEvents = PlaybackEventDispatcher(
        onEvent = onPlaybackEvent,
        onFailure = { event, failure ->
            onDiagnostic(
                VoicePlaybackDiagnostic.PlaybackEventHandlerFailed(
                    event = event,
                    message = failure.message ?: failure.javaClass.simpleName,
                ),
            )
        },
    )
    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is PlaybackCommand.Play -> playCommand(command)
                is PlaybackCommand.Drain -> drainCommand(command)
            }
        }
    }

    private var activeSessionId: Long? = null
    private var writerGeneration = WriterGeneration(0L)
    private var activeSink: VoicePcm16Sink? = null
    private var released = false
    private var nextPlaybackEpoch = PlaybackEpoch(0L)
    private var acceptingPlaybackEpoch: PlaybackEpoch? = null
    private val epochWriterGenerations = mutableMapOf<PlaybackEpoch, WriterGeneration>()
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

        var staleActiveWriterGeneration: WriterGeneration? = null
        var staleActiveSessionId: Long? = null
        val enqueued = synchronized(lock) {
            if (released || retirementInProgress || playbackRetirementFailed) {
                null
            } else if (sessionId != null && activeSessionId != sessionId) {
                staleActiveWriterGeneration = writerGeneration
                staleActiveSessionId = activeSessionId
                null
            } else {
                val existingEpoch = acceptingPlaybackEpoch
                val playbackEpoch = existingEpoch ?: PlaybackEpoch(nextPlaybackEpoch.value + 1L).also { epoch ->
                    nextPlaybackEpoch = epoch
                    acceptingPlaybackEpoch = epoch
                    epochWriterGenerations[epoch] = writerGeneration
                }
                val command = PlaybackCommand.Play(
                    pcm16 = pcm16,
                    writerGeneration = writerGeneration,
                )
                if (!commands.trySend(command).isSuccess) {
                    if (existingEpoch == null) {
                        acceptingPlaybackEpoch = null
                        epochWriterGenerations.remove(playbackEpoch)
                    }
                    null
                } else {
                    if (existingEpoch == null) {
                        playbackEvents.enqueue(VoicePlaybackEvent.Active(playbackEpoch))
                    }
                    command
                }
            }
        }
        playbackEvents.drain()
        if (enqueued == null) {
            staleActiveWriterGeneration?.let { activeWriterGeneration ->
                onDiagnostic(
                    VoicePlaybackDiagnostic.StaleChunkRejected(
                        writerGeneration = activeWriterGeneration,
                        activeWriterGeneration = activeWriterGeneration,
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
                writerGeneration = enqueued.writerGeneration,
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
                    writerGeneration = writerGeneration,
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
        val retiredWriterGeneration = retireCurrentWriterGeneration {
            if (released) return
        } ?: return
        onDiagnostic(VoicePlaybackDiagnostic.PlaybackSuppressed(retiredWriterGeneration.next()))
    }

    fun release(onPlaybackEventsDrained: () -> Unit = {}) {
        retireCurrentWriterGeneration {
            if (released) return
            released = true
            activeSessionId = null
        }
        commands.close()
        worker.cancel()
        onDiagnostic(VoicePlaybackDiagnostic.Released)
        playbackEvents.drainThrough(onPlaybackEventsDrained)
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
                            writerGeneration = command.writerGeneration,
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
            if (released || writerGeneration != command.writerGeneration) return
            playbackEvents.enqueue(VoicePlaybackEvent.DrainStarted(command.playbackEpoch))
            activeSink
        }
        playbackEvents.drain()
        val result = if (sink == null) {
            VoicePcm16Sink.DrainResult.Drained
        } else {
            VoicePcm16SinkLifecycle.awaitDrained(sink)
        }
        when (result) {
            VoicePcm16Sink.DrainResult.Drained -> {
                synchronized(lock) {
                    val completed = !released &&
                        !retirementInProgress &&
                        !playbackRetirementFailed &&
                        writerGeneration == command.writerGeneration &&
                        epochWriterGenerations[command.playbackEpoch] == command.writerGeneration
                    if (completed) {
                        epochWriterGenerations.remove(command.playbackEpoch)
                        playbackEvents.enqueue(VoicePlaybackEvent.Drained(command.playbackEpoch))
                    }
                }
                playbackEvents.drain()
            }
            VoicePcm16Sink.DrainResult.Interrupted -> Unit
            is VoicePcm16Sink.DrainResult.Failed -> {
                onDiagnostic(VoicePlaybackDiagnostic.SinkDrainFailed(result.message))
                retireWriterGenerationAfterFlush(command.writerGeneration, sink)
            }
        }
    }

    private fun getOrCreateSink(commandWriterGeneration: WriterGeneration): VoicePcm16Sink? {
        var staleActiveWriterGeneration: WriterGeneration? = null
        val currentSink = synchronized(lock) {
            if (released || retirementInProgress || playbackRetirementFailed ||
                writerGeneration != commandWriterGeneration
            ) {
                staleActiveWriterGeneration = writerGeneration
                null
            } else {
                activeSink
            }
        }
        if (staleActiveWriterGeneration != null) {
            emitStale(commandWriterGeneration)
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
                retireWriterGenerationAfterFlush(commandWriterGeneration, outcome.sinkRequiringRetirement)
                onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(outcome.message))
                return null
            }
        }

        var staleWriterGeneration: WriterGeneration? = null
        val selectedSink = synchronized(lock) {
            if (released || retirementInProgress || playbackRetirementFailed ||
                writerGeneration != commandWriterGeneration
            ) {
                staleWriterGeneration = writerGeneration
                null
            } else {
                activeSink ?: newSink.also { activeSink = it }
            }
        }

        if (selectedSink == null) {
            retireDetachedSink(newSink)
            onDiagnostic(
                VoicePlaybackDiagnostic.StaleChunkRejected(
                    writerGeneration = commandWriterGeneration,
                    activeWriterGeneration = staleWriterGeneration ?: currentWriterGeneration(),
                ),
            )
            return null
        }

        if (selectedSink !== newSink) retireDetachedSink(newSink)
        return selectedSink
    }

    private fun retireWriterGenerationAfterFlush(
        commandWriterGeneration: WriterGeneration,
        sink: VoicePcm16Sink?,
    ) {
        synchronized(retirementLock) {
            val retirement = synchronized(lock) {
                if (released || writerGeneration != commandWriterGeneration) return
                beginRetirementLocked()
            }
            finishRetirement(retirement, sink ?: retirement.sink)
        }
    }

    private inline fun retireCurrentWriterGeneration(updateState: () -> Unit): WriterGeneration? {
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
        val retiringWriterGeneration = writerGeneration
        retirementInProgress = true
        writerGeneration = writerGeneration.next()
        acceptingPlaybackEpoch = null
        val sink = activeSink
        activeSink = null
        return Retirement(
            writerGeneration = retiringWriterGeneration,
            sink = sink,
            playbackEpochs = epochWriterGenerations
                .filterValues { it == retiringWriterGeneration }
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
                    playbackEvents.enqueue(VoicePlaybackEvent.Drained(epoch))
                }
            }
            retirementInProgress = false
        }
        playbackEvents.drain()
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

    private fun isCurrent(commandWriterGeneration: WriterGeneration): Boolean = synchronized(lock) {
        !released && !retirementInProgress && !playbackRetirementFailed &&
            writerGeneration == commandWriterGeneration
    }

    private fun isCurrentSink(
        commandWriterGeneration: WriterGeneration,
        sink: VoicePcm16Sink,
    ): Boolean = synchronized(lock) {
        !released && !retirementInProgress && !playbackRetirementFailed &&
            writerGeneration == commandWriterGeneration && activeSink === sink
    }

    private fun emitStale(commandWriterGeneration: WriterGeneration) {
        onDiagnostic(
            VoicePlaybackDiagnostic.StaleChunkRejected(
                writerGeneration = commandWriterGeneration,
                activeWriterGeneration = currentWriterGeneration(),
            ),
        )
    }

    private fun currentWriterGeneration(): WriterGeneration = synchronized(lock) { writerGeneration }

    private sealed interface PlaybackCommand {
        data class Play(
            val pcm16: ByteArray,
            val writerGeneration: WriterGeneration,
        ) : PlaybackCommand

        data class Drain(
            val writerGeneration: WriterGeneration,
            val playbackEpoch: PlaybackEpoch,
        ) : PlaybackCommand
    }

    private data class Retirement(
        val writerGeneration: WriterGeneration,
        val sink: VoicePcm16Sink?,
        val playbackEpochs: List<PlaybackEpoch>,
    )
}
