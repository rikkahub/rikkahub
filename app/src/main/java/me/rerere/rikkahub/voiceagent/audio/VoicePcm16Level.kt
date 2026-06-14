package me.rerere.rikkahub.voiceagent.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class VoicePcm16Level(
    val samples: Int,
    val peak: Int,
    val rms: Int,
    val zeroCrossings: Int,
)

internal fun voicePcm16Level(pcm16: ByteArray): VoicePcm16Level {
    val samples = pcm16.size / 2
    if (samples == 0) {
        return VoicePcm16Level(samples = 0, peak = 0, rms = 0, zeroCrossings = 0)
    }

    var peak = 0
    var sumSquares = 0.0
    var lastSign = 0
    var zeroCrossings = 0

    for (index in 0 until samples) {
        val byteIndex = index * 2
        val sample = ((pcm16[byteIndex + 1].toInt() shl 8) or (pcm16[byteIndex].toInt() and 0xFF)).toShort().toInt()
        val magnitude = if (sample == Short.MIN_VALUE.toInt()) 32_768 else kotlin.math.abs(sample)
        peak = maxOf(peak, magnitude)
        sumSquares += sample.toDouble() * sample.toDouble()

        val sign = when {
            sample > 0 -> 1
            sample < 0 -> -1
            else -> 0
        }
        if (sign != 0) {
            if (lastSign != 0 && sign != lastSign) {
                zeroCrossings += 1
            }
            lastSign = sign
        }
    }

    return VoicePcm16Level(
        samples = samples,
        peak = peak,
        rms = sqrt(sumSquares / samples).roundToInt(),
        zeroCrossings = zeroCrossings,
    )
}
