package me.rerere.rikkahub.data.ai.tools.local

import android.media.AudioAttributes
import android.media.MediaPlayer

class MediaPlayerHolder {
    private var player: MediaPlayer? = null

    /** Replaces any current playback. Blocks on prepare(); call from a background dispatcher (Tools execute on Dispatchers.IO). */
    @Synchronized
    fun play(source: String) {
        stopInternal()
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(source)
            setOnCompletionListener { stopInternal() }
            setOnErrorListener { _, _, _ -> stopInternal(); true }
            prepare()
            start()
        }
        player = mp
    }

    /** Stops and releases the current player. Returns true if a player was actively playing. */
    @Synchronized
    fun stop(): Boolean {
        val wasPlaying = player?.isPlaying == true
        stopInternal()
        return wasPlaying
    }

    @Synchronized
    private fun stopInternal() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        player = null
    }
}
