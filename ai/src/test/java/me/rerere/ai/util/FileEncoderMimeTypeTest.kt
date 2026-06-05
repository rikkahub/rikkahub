package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileEncoderMimeTypeTest {

    @Test
    fun `hevc-coded heif brands should be recognized as image heic`() {
        // These ISO-BMFF brands are all HEVC-coded HEIF and were dropped by the old
        // exact-literal "ftypheic" check, falling through to the error path.
        for (brand in listOf("heic", "heix", "heim", "heis", "hevc", "hevx")) {
            assertEquals(
                "brand $brand should map to image/heic",
                "image/heic",
                sniffImageMimeType(ftypHeader(brand))
            )
        }
    }

    @Test
    fun `generic heif brands should be recognized as image heif`() {
        for (brand in listOf("mif1", "msf1")) {
            assertEquals(
                "brand $brand should map to image/heif",
                "image/heif",
                sniffImageMimeType(ftypHeader(brand))
            )
        }
    }

    @Test
    fun `non-heif iso-bmff brands should not be misclassified as images`() {
        // MP4/MOV containers also use ftyp boxes; they must not be treated as images.
        for (brand in listOf("isom", "mp42", "qt  ")) {
            assertNull(
                "brand $brand should not be recognized",
                sniffImageMimeType(ftypHeader(brand))
            )
        }
    }

    @Test
    fun `jpeg header should be recognized`() {
        val bytes = ByteArray(16)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        assertEquals("image/jpeg", sniffImageMimeType(bytes))
    }

    @Test
    fun `png header should be recognized`() {
        val bytes = ByteArray(16)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(bytes)
        assertEquals("image/png", sniffImageMimeType(bytes))
    }

    @Test
    fun `webp header should be recognized`() {
        val bytes = ByteArray(16)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        assertEquals("image/webp", sniffImageMimeType(bytes))
    }

    @Test
    fun `gif headers should be recognized`() {
        for (sig in listOf("GIF89a", "GIF87a")) {
            val bytes = ByteArray(16)
            sig.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
            assertEquals("image/gif", sniffImageMimeType(bytes))
        }
    }

    @Test
    fun `unrecognized header should return null`() {
        val bytes = ByteArray(16) { 0x00 }
        assertNull(sniffImageMimeType(bytes))
    }

    private fun ftypHeader(brand: String): ByteArray {
        require(brand.length == 4) { "brand must be 4 chars" }
        val bytes = ByteArray(16)
        // bytes[0..4) is the box-size field in a real file; arbitrary here.
        bytes[3] = 0x18
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
        brand.toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        return bytes
    }
}
