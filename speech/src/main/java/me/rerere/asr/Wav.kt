package me.rerere.asr

import java.io.ByteArrayOutputStream

/**
 * 把 raw PCM16 little-endian 数据封装成最小 WAV (RIFF/WAVE/fmt/data)。
 * AudioRecord 输出的是裸 PCM, 只接受 WAV 的 HTTP 识别接口需要自己补头。
 */
internal fun pcm16ToWav(
    pcm: ByteArray,
    sampleRate: Int,
    channels: Int = 1,
    bitsPerSample: Int = 16,
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val out = ByteArrayOutputStream(44 + dataSize)

    // RIFF header
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    out.writeIntLE(36 + dataSize) // chunk size = file size - 8
    out.write("WAVE".toByteArray(Charsets.US_ASCII))
    // fmt chunk
    out.write("fmt ".toByteArray(Charsets.US_ASCII))
    out.writeIntLE(16) // PCM fmt chunk size
    out.writeShortLE(1) // audio format = PCM
    out.writeShortLE(channels)
    out.writeIntLE(sampleRate)
    out.writeIntLE(byteRate)
    out.writeShortLE(blockAlign)
    out.writeShortLE(bitsPerSample)
    // data chunk
    out.write("data".toByteArray(Charsets.US_ASCII))
    out.writeIntLE(dataSize)
    out.write(pcm)
    return out.toByteArray()
}

private fun ByteArrayOutputStream.writeIntLE(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}

private fun ByteArrayOutputStream.writeShortLE(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}
