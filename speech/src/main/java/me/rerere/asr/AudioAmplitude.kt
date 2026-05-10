package me.rerere.asr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

private const val MAX_AMPLITUDES = 32

fun calculateRmsAmplitude(buffer: ByteArray, readBytes: Int): Float {
    val shorts = ByteBuffer.wrap(buffer, 0, readBytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asShortBuffer()
    var sum = 0.0
    val count = shorts.remaining()
    if (count == 0) return 0f
    for (i in 0 until count) {
        val sample = shorts[i].toDouble()
        sum += sample * sample
    }
    val rms = sqrt(sum / count)
    val linear = (rms / Short.MAX_VALUE).toFloat()
    if (linear < 1e-6f) return 0f
    val db = 20f * log10(linear)
    // -60dB ~ 0dB -> 0f ~ 1f
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

fun List<Float>.appendAmplitude(amplitude: Float): List<Float> {
    val list = if (size >= MAX_AMPLITUDES) drop(1) else this
    return list + amplitude
}
