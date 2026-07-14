package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidVoicePlaybackTracks(
    private val audioAttributes: () -> AudioAttributes,
    private val onAssistantPlaybackError: (String) -> Unit,
) {
    private val lock = Any()
    private var assistantAudioTrack: AudioTrack? = null
    private var released = false

    fun createAssistantSinkOrNull(): VoicePcm16Sink? {
        val track = getOrCreatePlaybackTrack() ?: return null
        return AndroidAudioTrackSink(track = track)
    }

    fun markReleased() {
        synchronized(lock) {
            released = true
        }
    }

    fun releaseAll() {
        val assistant: AudioTrack?
        synchronized(lock) {
            released = true
            assistant = assistantAudioTrack
        }
        assistant?.stopSafely()
        assistant?.let(::releaseTrackSafely)
    }

    private fun getOrCreatePlaybackTrack(): AudioTrack? {
        val existingTrack = synchronized(lock) { assistantAudioTrack }
        if (existingTrack != null) {
            return currentPlaybackTrack(existingTrack)
        }

        val newTrack = createAudioTrackOrNull() ?: return null
        var selectedTrack: AudioTrack? = null
        var shouldReleaseNewTrack = false
        synchronized(lock) {
            val currentTrack = assistantAudioTrack
            if (released) {
                shouldReleaseNewTrack = true
            } else if (currentTrack == null) {
                assistantAudioTrack = newTrack
                selectedTrack = newTrack
            } else {
                selectedTrack = currentTrack
                shouldReleaseNewTrack = true
            }
        }

        if (shouldReleaseNewTrack) {
            newTrack.releaseSafely()
        }
        return currentPlaybackTrack(selectedTrack ?: return null)
    }

    private fun currentPlaybackTrack(track: AudioTrack): AudioTrack? =
        synchronized(lock) {
            if (!released && assistantAudioTrack === track) {
                track
            } else {
                null
            }
        }

    private fun createAudioTrackOrNull(): AudioTrack? {
        val bufferSize = playbackBufferSizeOrNull() ?: return null
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val track = runCatching {
            AudioTrack(
                audioAttributes(),
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.onFailure {
            Log.w(TAG, "AudioTrack creation failed", it)
            onAssistantPlaybackError("AudioTrack creation failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
            onAssistantPlaybackError("AudioTrack initialization failed: state=${track.state}")
            track.releaseSafely()
            return null
        }

        return track
    }

    private fun removeTrack(track: AudioTrack) {
        synchronized(lock) {
            if (assistantAudioTrack === track) {
                assistantAudioTrack = null
            }
        }
    }

    private fun releaseTrackSafely(track: AudioTrack) {
        if (track.releaseSafely()) {
            removeTrack(track)
        }
    }

    private fun AudioTrack.playSafely(): Boolean {
        return runCatching {
            if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                play()
            }
            true
        }.onFailure {
            Log.w(TAG, "AudioTrack play failed", it)
            onAssistantPlaybackError("AudioTrack play failed: ${it.message ?: it.javaClass.simpleName}")
            releaseTrackSafely(this)
        }.getOrDefault(false)
    }

    private inner class AndroidAudioTrackSink(
        private val track: AudioTrack,
    ) : VoicePcm16Sink {
        private val interrupted = AtomicBoolean(false)
        private val drainState = PlaybackDrainState(
            readPlaybackHead = { track.playbackHeadPosition },
            isInterrupted = interrupted::get,
            isPlaybackTrackCurrent = { currentPlaybackTrack(track) != null },
            poll = { Thread.sleep(PLAYBACK_DRAIN_POLL_MS) },
        )
        private val retirement = AudioTrackRetirement(
            pause = track::pauseSafely,
            flush = track::flushSafely,
            stop = track::stopSafely,
            release = track::releaseSafely,
            removeTrack = { removeTrack(track) },
        )

        override fun start(): VoicePcm16Sink.StartResult {
            interrupted.set(false)
            return if (track.playSafely()) {
                drainState.onStarted()
                VoicePcm16Sink.StartResult.Started
            } else {
                VoicePcm16Sink.StartResult.Failed("AudioTrack play failed")
            }
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            if (interrupted.get()) {
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            if (!track.playSafely()) {
                return VoicePcm16Sink.WriteResult.Failed("AudioTrack play failed")
            }

            var offset = 0
            var zeroWrites = 0
            while (offset < pcm16.size && !interrupted.get() && currentPlaybackTrack(track) != null) {
                val remaining = pcm16.size - offset
                val writeResult = try {
                    track.write(pcm16, offset, remaining, AudioTrack.WRITE_BLOCKING)
                } catch (e: RuntimeException) {
                    if (interrupted.get()) {
                        return VoicePcm16Sink.WriteResult.Interrupted
                    }
                    Log.w(TAG, "AudioTrack write failed", e)
                    releaseTrackSafely(track)
                    return VoicePcm16Sink.WriteResult.Failed(e.message ?: e.javaClass.simpleName)
                }

                when {
                    interrupted.get() -> {
                        return VoicePcm16Sink.WriteResult.Interrupted
                    }
                    writeResult < 0 -> {
                        Log.w(TAG, "AudioTrack write error: $writeResult")
                        releaseTrackSafely(track)
                        return VoicePcm16Sink.WriteResult.Failed("AudioTrack write error: $writeResult")
                    }
                    writeResult == 0 -> {
                        zeroWrites += 1
                        if (zeroWrites >= MAX_BLOCKING_ZERO_WRITES) {
                            releaseTrackSafely(track)
                            return VoicePcm16Sink.WriteResult.Failed(
                                "AudioTrack write made no progress after $zeroWrites attempts",
                            )
                        }
                        Thread.yield()
                    }
                    else -> {
                        offset += writeResult.coerceAtMost(remaining)
                        zeroWrites = 0
                    }
                }
            }

            return when {
                offset == pcm16.size -> {
                    drainState.onBytesWritten(offset)
                    VoicePcm16Sink.WriteResult.Written(offset)
                }
                interrupted.get() -> VoicePcm16Sink.WriteResult.Interrupted
                else -> VoicePcm16Sink.WriteResult.Failed(
                    "AudioTrack write interrupted after $offset of ${pcm16.size} bytes",
                )
            }
        }

        override fun awaitDrained(): VoicePcm16Sink.DrainResult = drainState.awaitDrained()

        override fun pauseAndFlush() {
            interrupted.set(true)
            retirement.pauseAndFlush()
        }

        override fun stopAndRelease() {
            interrupted.set(true)
            retirement.stopAndRelease()
        }
    }

    private companion object {
        const val TAG = "AndroidVoicePlaybackTracks"
        const val PLAYBACK_SAMPLE_RATE = 24_000
        const val MIN_PLAYBACK_BUFFER_BYTES = 4_800
        const val MAX_BLOCKING_ZERO_WRITES = 16
        const val PLAYBACK_DRAIN_POLL_MS = 10L

        fun playbackBufferSizeOrNull(): Int? {
            val bufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                Log.w(TAG, "AudioTrack min buffer size failed: $bufferSize")
                return null
            }
            return bufferSize.coerceAtLeast(MIN_PLAYBACK_BUFFER_BYTES)
        }
    }
}

private fun AudioTrack.pauseSafely(): Boolean = runCatching { pause() }.isSuccess

private fun AudioTrack.flushSafely(): Boolean = runCatching { flush() }.isSuccess

private fun AudioTrack.stopSafely(): Boolean = runCatching { stop() }.isSuccess

private fun AudioTrack.releaseSafely(): Boolean = runCatching { release() }.isSuccess
