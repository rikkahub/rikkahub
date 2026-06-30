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
    private var localCueAudioTrack: AudioTrack? = null
    private var released = false

    fun createAssistantSinkOrNull(): VoicePcm16Sink? {
        val track = getOrCreatePlaybackTrack(AndroidPlaybackTrackOwner.Assistant) ?: return null
        return AndroidAudioTrackSink(
            track = track,
            owner = AndroidPlaybackTrackOwner.Assistant,
        )
    }

    fun createLocalCueSinkOrNull(): VoicePcm16Sink? {
        val track = createLocalCuePlaybackTrack() ?: return null
        return AndroidAudioTrackSink(
            track = track,
            owner = AndroidPlaybackTrackOwner.LocalCue,
        )
    }

    fun releaseAll() {
        val assistant: AudioTrack?
        val localCue: AudioTrack?
        synchronized(lock) {
            if (released) {
                return
            }
            released = true
            assistant = assistantAudioTrack
            localCue = localCueAudioTrack
            assistantAudioTrack = null
            localCueAudioTrack = null
        }
        assistant?.stopSafely()
        assistant?.releaseSafely()
        if (localCue !== assistant) {
            localCue?.stopSafely()
            localCue?.releaseSafely()
        }
    }

    private fun getOrCreatePlaybackTrack(owner: AndroidPlaybackTrackOwner): AudioTrack? {
        val existingTrack = synchronized(lock) { playbackTrackForLocked(owner) }
        if (existingTrack != null) {
            return currentPlaybackTrack(existingTrack, owner)
        }

        val newTrack = createAudioTrackOrNull(owner) ?: return null
        var selectedTrack: AudioTrack? = null
        var shouldReleaseNewTrack = false
        synchronized(lock) {
            val currentTrack = playbackTrackForLocked(owner)
            if (released) {
                shouldReleaseNewTrack = true
            } else if (currentTrack == null) {
                setPlaybackTrackForLocked(owner, newTrack)
                selectedTrack = newTrack
            } else {
                selectedTrack = currentTrack
                shouldReleaseNewTrack = true
            }
        }

        if (shouldReleaseNewTrack) {
            newTrack.releaseSafely()
        }
        return currentPlaybackTrack(selectedTrack ?: return null, owner)
    }

    private fun createLocalCuePlaybackTrack(): AudioTrack? {
        val newTrack = createAudioTrackOrNull(AndroidPlaybackTrackOwner.LocalCue) ?: return null
        var shouldReleaseNewTrack = false
        synchronized(lock) {
            if (released) {
                shouldReleaseNewTrack = true
            } else {
                localCueAudioTrack = newTrack
            }
        }
        if (shouldReleaseNewTrack) {
            newTrack.releaseSafely()
            return null
        }
        return currentPlaybackTrack(newTrack, AndroidPlaybackTrackOwner.LocalCue)
    }

    private fun currentPlaybackTrack(track: AudioTrack, owner: AndroidPlaybackTrackOwner): AudioTrack? =
        synchronized(lock) {
            if (!released && playbackTrackForLocked(owner) === track) {
                track
            } else {
                null
            }
        }

    private fun playbackTrackForLocked(owner: AndroidPlaybackTrackOwner): AudioTrack? = when (owner) {
        AndroidPlaybackTrackOwner.Assistant -> assistantAudioTrack
        AndroidPlaybackTrackOwner.LocalCue -> localCueAudioTrack
    }

    private fun setPlaybackTrackForLocked(owner: AndroidPlaybackTrackOwner, track: AudioTrack?) {
        when (owner) {
            AndroidPlaybackTrackOwner.Assistant -> assistantAudioTrack = track
            AndroidPlaybackTrackOwner.LocalCue -> localCueAudioTrack = track
        }
    }

    private fun createAudioTrackOrNull(owner: AndroidPlaybackTrackOwner): AudioTrack? {
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
            if (owner == AndroidPlaybackTrackOwner.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack creation failed")
            } else {
                onAssistantPlaybackError("AudioTrack creation failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
            if (owner == AndroidPlaybackTrackOwner.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack initialization failed")
            } else {
                onAssistantPlaybackError("AudioTrack initialization failed: state=${track.state}")
            }
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
            if (localCueAudioTrack === track) {
                localCueAudioTrack = null
            }
        }
    }

    private fun AudioTrack.playSafely(owner: AndroidPlaybackTrackOwner): Boolean {
        return runCatching {
            if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                play()
            }
            true
        }.onFailure {
            Log.w(TAG, "AudioTrack play failed", it)
            if (owner == AndroidPlaybackTrackOwner.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack play failed")
            } else {
                onAssistantPlaybackError("AudioTrack play failed: ${it.message ?: it.javaClass.simpleName}")
            }
            removeTrack(this)
            releaseSafely()
        }.getOrDefault(false)
    }

    private inner class AndroidAudioTrackSink(
        private val track: AudioTrack,
        private val owner: AndroidPlaybackTrackOwner,
    ) : VoicePcm16Sink {
        private val interrupted = AtomicBoolean(false)

        override fun start(): VoicePcm16Sink.StartResult {
            interrupted.set(false)
            return if (track.playSafely(owner)) {
                VoicePcm16Sink.StartResult.Started
            } else {
                VoicePcm16Sink.StartResult.Failed("AudioTrack play failed")
            }
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            if (interrupted.get()) {
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            if (!track.playSafely(owner)) {
                return VoicePcm16Sink.WriteResult.Failed("AudioTrack play failed")
            }

            var offset = 0
            var zeroWrites = 0
            while (offset < pcm16.size && !interrupted.get() && currentPlaybackTrack(track, owner) != null) {
                val remaining = pcm16.size - offset
                val writeResult = try {
                    track.write(pcm16, offset, remaining, AudioTrack.WRITE_BLOCKING)
                } catch (e: RuntimeException) {
                    if (interrupted.get()) {
                        return VoicePcm16Sink.WriteResult.Interrupted
                    }
                    Log.w(TAG, "AudioTrack write failed", e)
                    removeTrack(track)
                    track.releaseSafely()
                    return VoicePcm16Sink.WriteResult.Failed(e.message ?: e.javaClass.simpleName)
                }

                when {
                    interrupted.get() -> {
                        return VoicePcm16Sink.WriteResult.Interrupted
                    }
                    writeResult < 0 -> {
                        Log.w(TAG, "AudioTrack write error: $writeResult")
                        removeTrack(track)
                        track.releaseSafely()
                        return VoicePcm16Sink.WriteResult.Failed("AudioTrack write error: $writeResult")
                    }
                    writeResult == 0 -> {
                        zeroWrites += 1
                        if (zeroWrites >= MAX_BLOCKING_ZERO_WRITES) {
                            removeTrack(track)
                            track.releaseSafely()
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
                offset == pcm16.size -> VoicePcm16Sink.WriteResult.Written(offset)
                interrupted.get() -> VoicePcm16Sink.WriteResult.Interrupted
                else -> VoicePcm16Sink.WriteResult.Failed(
                    "AudioTrack write interrupted after $offset of ${pcm16.size} bytes",
                )
            }
        }

        override fun pauseAndFlush() {
            interrupted.set(true)
            track.pauseSafely()
            track.flushSafely()
        }

        override fun stopAndRelease() {
            interrupted.set(true)
            track.stopSafely()
            track.releaseSafely()
            removeTrack(track)
        }
    }

    private enum class AndroidPlaybackTrackOwner {
        Assistant,
        LocalCue,
    }

    private companion object {
        const val TAG = "AndroidVoicePlaybackTracks"
        const val PLAYBACK_SAMPLE_RATE = 24_000
        const val MIN_PLAYBACK_BUFFER_BYTES = 4_800
        const val MAX_BLOCKING_ZERO_WRITES = 16

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

private fun AudioTrack.pauseSafely() {
    runCatching { pause() }
}

private fun AudioTrack.flushSafely() {
    runCatching { flush() }
}

private fun AudioTrack.stopSafely() {
    runCatching { stop() }
}

private fun AudioTrack.releaseSafely() {
    runCatching { release() }
}
