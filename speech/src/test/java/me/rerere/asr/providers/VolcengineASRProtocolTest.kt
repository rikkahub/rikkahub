package me.rerere.asr.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRVoiceTurn
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class VolcengineASRProtocolTest {
    @Test fun `request enables second pass VAD and clamps silence duration`() {
        for ((duration, expected) in listOf(800 to 800, 0 to 300, 9000 to 5000)) {
            val frame = VolcengineASRProtocol.initialFrame(ASRProviderSetting.Volcengine(silenceDurationMs = duration))
            assertEquals(0x11, frame[0].toInt())
            assertEquals(0x10, frame[1].toInt())
            assertEquals(0x11, frame[2].toInt())
            assertEquals(frame.size - 8, ByteBuffer.wrap(frame, 4, 4).int)
            val json = GZIPInputStream(frame.copyOfRange(8, frame.size).inputStream()).use {
                Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
            }
            val request = json.getValue("request").jsonObject
            assertEquals("true", request.getValue("enable_nonstream").jsonPrimitive.content)
            assertEquals("true", request.getValue("show_utterances").jsonPrimitive.content)
            assertEquals(expected.toString(), request.getValue("end_window_size").jsonPrimitive.content)
            assertEquals("full", request.getValue("result_type").jsonPrimitive.content)
        }
    }

    @Test fun `audio and terminating packets use distinct flags and exact payload lengths`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val audio = VolcengineASRProtocol.audioFrame(pcm)
        assertEquals(0x20, audio[1].toInt())
        assertArrayEquals(pcm, audio.copyOfRange(8, audio.size))
        val last = VolcengineASRProtocol.audioFrame(ByteArray(0), last = true)
        assertEquals(0x22, last[1].toInt())
        assertEquals(0, ByteBuffer.wrap(last, 4, 4).int)
    }

    @Test fun `decodes compressed final response with sequence and extended header`() {
        val data = serverFrame("""{"result":{"text":"你好"}}""", flags = 3, compressed = true, extended = true)
        val response = VolcengineASRProtocol.decode(data)
        assertTrue(response.isLast)
        assertEquals("你好", response.result!!.getValue("text").jsonPrimitive.content)
    }

    @Test fun `decodes intermediate response and optional event number`() {
        val response = VolcengineASRProtocol.decode(serverFrame("""{"result":{"text":"中间"}}""", flags = 5))
        assertFalse(response.isLast)
        assertEquals("中间", response.result!!.getValue("text").jsonPrimitive.content)
    }

    @Test fun `compressed server errors retain code and message`() {
        val response = VolcengineASRProtocol.decode(serverFrame("invalid key", type = 15, compressed = true))
        assertTrue(response.error!!.contains("45000001"))
        assertTrue(response.error!!.contains("invalid key"))
    }

    @Test fun `rejects truncated headers sequences and payloads`() {
        val valid = serverFrame("""{"result":{"text":"hello"}}""", flags = 1)
        for (length in listOf(0, 3, 6, 10, valid.size - 1)) {
            assertTrue(runCatching { VolcengineASRProtocol.decode(valid.copyOf(length)) }.isFailure)
        }
    }

    @Test fun `partial result waits for definite even if second pass adjusts timestamp`() {
        val preview = Json.parseToJsonElement("""{"text":"你好","utterances":[{"start_time":120,"text":"你好","definite":false}]}""").jsonObject
        val initial = VolcengineASRProtocol.voiceTurn(ASRVoiceTurn(), preview)
        assertNotNull(initial.itemId)
        assertFalse(initial.isComplete)
        val final = Json.parseToJsonElement("""{"text":"您好。","utterances":[{"start_time":100,"end_time":900,"text":"您好。","definite":true}]}""").jsonObject
        val completed = VolcengineASRProtocol.voiceTurn(initial, final)
        assertTrue(completed.isComplete)
        assertEquals(initial.itemId, completed.itemId)
        assertEquals("您好。", completed.finalText)
    }

    @Test fun `cumulative and repeated results cannot replace an already finalized turn`() {
        val result = Json.parseToJsonElement("""{"utterances":[{"text":"第一句","definite":true},{"text":"第二句","definite":true}]}""").jsonObject
        val turn = VolcengineASRProtocol.voiceTurn(ASRVoiceTurn(), result)
        assertEquals("第一句", turn.finalText)
        assertEquals(turn, VolcengineASRProtocol.voiceTurn(turn, result))
        assertEquals(turn, VolcengineASRProtocol.voiceTurn(turn, Json.parseToJsonElement("""{"utterances":[{"text":"其他","definite":true}]}""").jsonObject))
    }

    @Test fun `preview without utterances marks active speech and empty definite completes`() {
        val preview = Json.parseToJsonElement("""{"text":"你好"}""").jsonObject
        val turn = VolcengineASRProtocol.voiceTurn(ASRVoiceTurn(), preview)
        assertNotNull(turn.itemId)
        assertFalse(turn.isComplete)
        val emptyFinal = Json.parseToJsonElement("""{"utterances":[{"text":"","definite":true}]}""").jsonObject
        assertTrue(VolcengineASRProtocol.voiceTurn(turn, emptyFinal).isComplete)
        assertEquals(ASRVoiceTurn(), VolcengineASRProtocol.voiceTurn(ASRVoiceTurn(), Json.parseToJsonElement("""{"text":""}""").jsonObject))
    }

    private fun serverFrame(json: String, flags: Int = 0, type: Int = 9, compressed: Boolean = false, extended: Boolean = false): ByteArray {
        var payload = json.toByteArray()
        if (compressed) {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(payload) }
            payload = output.toByteArray()
        }
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(if (extended) 0x12 else 0x11, ((type shl 4) or flags).toByte(), if (compressed) 0x11 else 0x10, 0))
        if (extended) output.write(ByteArray(4))
        fun int(value: Int) { output.write(ByteBuffer.allocate(4).putInt(value).array()) }
        if (type == 15) int(45000001)
        if (type == 9 && flags and 1 != 0) int(if (flags and 2 != 0) -42 else 42)
        if (type == 9 && flags and 4 != 0) int(0)
        int(payload.size)
        output.write(payload)
        return output.toByteArray()
    }
}
