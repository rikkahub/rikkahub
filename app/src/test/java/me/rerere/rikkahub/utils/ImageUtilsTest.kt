package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.text.Charsets.ISO_8859_1

class ImageUtilsTest {
    @Test
    fun `should prefer ccv3 chunk over chara chunk`() {
        val pngBytes = buildPngWithTextChunks(
            "chara" to "old-card",
            "ccv3" to "new-card",
        )

        val result = ImageUtils.extractTavernCharacterMetaFromPngBytes(pngBytes)

        assertEquals("new-card", result)
    }

    @Test
    fun `should fall back to legacy chara payload inside text chunk`() {
        val pngBytes = buildPngWithTextChunks(
            "Comment" to "[chara: legacy-card]",
        )

        val result = ImageUtils.extractTavernCharacterMetaFromPngBytes(pngBytes)

        assertEquals("legacy-card", result)
    }

    @Test
    fun `should embed and extract tavern character metadata from png`() {
        val sourcePng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO8B9pQAAAAASUVORK5CYII="
        )
        val json = """{"spec":"chara_card_v2","data":{"name":"Test"}}"""

        val embedded = ImageUtils.embedTavernCharacterMetaIntoPngBytes(sourcePng, json)
        val extracted = ImageUtils.extractTavernCharacterMetaFromPngBytes(embedded)

        assertEquals(json, extracted)
    }

    @Test
    fun `base64 encoded tavern character metadata should preserve unicode text`() {
        val sourcePng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO8B9pQAAAAASUVORK5CYII="
        )
        val json = """{"spec":"chara_card_v2","data":{"name":"测试角色"}}"""

        val embedded = ImageUtils.embedTavernCharacterMetaIntoPngBytes(sourcePng, json.base64Encode())
        val extracted = ImageUtils.extractTavernCharacterMetaFromPngBytes(embedded).base64Decode()

        assertEquals(json, extracted)
    }

    private fun buildPngWithTextChunks(vararg chunks: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        chunks.forEach { (keyword, text) ->
            writeChunk(
                output = output,
                type = "tEXt",
                data = (keyword + "\u0000" + text).toByteArray(ISO_8859_1),
            )
        }
        writeChunk(output = output, type = "IEND", data = ByteArray(0))
        return output.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        output.write(intToBytes(data.size))
        output.write(type.toByteArray(Charsets.US_ASCII))
        output.write(data)
        output.write(byteArrayOf(0, 0, 0, 0))
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
