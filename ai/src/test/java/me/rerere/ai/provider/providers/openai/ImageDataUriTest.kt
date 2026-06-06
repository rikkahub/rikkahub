package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageDataUriTest {
    @Test
    fun png_extracts_base64_and_mime() {
        val r = parseImageDataUri("data:image/png;base64,AAAB")
        assertEquals("image/png", r!!.mime)
        assertEquals("AAAB", r.base64)
    }

    @Test
    fun jpeg_extracts_base64_and_mime() {
        val r = parseImageDataUri("data:image/jpeg;base64,ZZZZ")
        assertEquals("image/jpeg", r!!.mime)
        assertEquals("ZZZZ", r.base64)
    }

    @Test
    fun webp_extracts() {
        val r = parseImageDataUri("data:image/webp;base64,WWWW")
        assertEquals("image/webp", r!!.mime)
        assertEquals("WWWW", r.base64)
    }

    @Test
    fun non_data_uri_returns_null() {
        assertNull(parseImageDataUri("https://example.com/x.png"))
    }

    @Test
    fun malformed_returns_null() {
        assertNull(parseImageDataUri("data:image/png;base64"))
    }
}
