package me.rerere.asr.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRVoiceTurn
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** V3 binary framing and full-result decoding, shared by dictation and voice mode. */
internal object VolcengineASRProtocol {
    fun request(provider: ASRProviderSetting.Volcengine): ByteArray = buildJsonObject {
        putJsonObject("user") { put("uid", "rikkahub") }
        putJsonObject("audio") {
            put("format", "pcm")
            put("codec", "raw")
            put("rate", 16000)
            put("bits", 16)
            put("channel", 1)
            if (provider.language.isNotBlank()) put("language", provider.language)
        }
        putJsonObject("request") {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("show_utterances", true)
            put("result_type", "full")
            // Second-pass recognition enables VAD and marks its finalized sentences definite=true.
            put("enable_nonstream", true)
            put("end_window_size", provider.silenceDurationMs.coerceIn(300, 5000))
        }
    }.toString().toByteArray(Charsets.UTF_8)

    fun initialFrame(provider: ASRProviderSetting.Volcengine): ByteArray = frame(1, 0, 1, 1, gzip(request(provider)))
    fun audioFrame(pcm: ByteArray, last: Boolean = false): ByteArray = frame(2, if (last) 2 else 0, 0, 0, pcm)

    private fun frame(type: Int, flags: Int, serialization: Int, compression: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0x11, ((type shl 4) or flags).toByte(), ((serialization shl 4) or compression).toByte(), 0) +
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array() + payload

    data class Response(val result: JsonObject?, val isLast: Boolean, val error: String? = null)

    fun decode(data: ByteArray): Response {
        require(data.size >= 4) { "ASR response header is truncated" }
        val headerSize = (data[0].toInt() and 15) * 4
        require((data[0].toInt() ushr 4) == 1 && headerSize >= 4 && headerSize <= data.size) { "Invalid ASR response header" }
        val type = (data[1].toInt() and 255) ushr 4
        val flags = data[1].toInt() and 15
        val compression = data[2].toInt() and 15
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).apply { position(headerSize) }
        fun readInt(): Int {
            require(buffer.remaining() >= 4) { "ASR response is truncated" }
            return buffer.int
        }
        require(type == 9 || type == 15) { "Unsupported ASR response type: $type" }
        val code = if (type == 15) readInt() else 0
        val sequence = if (type == 9 && flags and 1 != 0) readInt() else 0
        if (type == 9 && flags and 4 != 0) readInt() // Optional event number.
        val size = readInt()
        require(size >= 0 && size <= buffer.remaining()) { "Invalid ASR payload size" }
        var payload = ByteArray(size).also { buffer.get(it) }
        payload = when (compression) {
            0 -> payload
            1 -> GZIPInputStream(payload.inputStream()).use { it.readBytes() }
            else -> error("Unsupported ASR compression: $compression")
        }
        val text = payload.toString(Charsets.UTF_8)
        if (type == 15) return Response(null, true, "ASR error $code: $text")
        val json = if (text.isBlank()) null else Json.parseToJsonElement(text).jsonObject
        return Response(json?.get("result")?.jsonObject, flags and 2 != 0 || sequence < 0)
    }

    fun voiceTurn(current: ASRVoiceTurn, result: JsonObject?): ASRVoiceTurn {
        if (result == null || current.isComplete) return current
        // result_type=full keeps sentence order. Retain the first sentence's identity even if
        // its timestamp changes during second-pass correction; each voice connection owns one turn.
        val first = result["utterances"]?.jsonArray?.firstOrNull()?.jsonObject
        val text = first?.get("text")?.jsonPrimitive?.contentOrNull
        val definite = first?.get("definite")?.jsonPrimitive?.booleanOrNull == true
        val id = current.itemId ?: "volc:${first?.get("start_time")?.jsonPrimitive?.contentOrNull ?: "0"}"
        val hasSpeech = !text.isNullOrBlank() || !result["text"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        val turn = if (hasSpeech || definite) current.started(id) else current
        return if (definite && text != null) turn.stopped(id).completed(id, text) else turn
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }
}
