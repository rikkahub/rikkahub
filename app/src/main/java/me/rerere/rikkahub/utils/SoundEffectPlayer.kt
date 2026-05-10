package me.rerere.rikkahub.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes

class SoundEffectPlayer(private val context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedSounds = mutableMapOf<Int, Int>()

    fun preload(@RawRes vararg resIds: Int) {
        for (resId in resIds) {
            if (resId !in loadedSounds) {
                loadedSounds[resId] = soundPool.load(context, resId, 1)
            }
        }
    }

    fun play(@RawRes resId: Int, volume: Float = 1f) {
        val soundId = loadedSounds[resId] ?: soundPool.load(context, resId, 1).also {
            loadedSounds[resId] = it
        }
        soundPool.play(soundId, volume, volume, 0, 0, 1f)
    }

    fun release() {
        soundPool.release()
        loadedSounds.clear()
    }
}
