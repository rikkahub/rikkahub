package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 25 — NFC tooling. The NdefMessage encode/decode round-trip and the reader-mode
 * Activity flow need instrumented tests (they touch android.nfc.* concrete classes); this
 * covers the pure pieces: timeout validation and the RTD payload decoders.
 */
class NfcToolsTest {

    @Test fun `timeout validation rejects out-of-range`() {
        assertEquals("timeout_seconds must be between 5 and 120", validateNfcTimeout(4))
        assertEquals("timeout_seconds must be between 5 and 120", validateNfcTimeout(121))
        assertEquals("timeout_seconds must be between 5 and 120", validateNfcTimeout(0))
    }

    @Test fun `timeout validation accepts in-range`() {
        assertNull(validateNfcTimeout(5))
        assertNull(validateNfcTimeout(30))
        assertNull(validateNfcTimeout(120))
    }

    @Test fun `RTD_TEXT payload decodes UTF-8 with language code`() {
        // status byte 0x02 = UTF-8, lang length 2; "en" + "hello"
        val payload = byteArrayOf(0x02, 'e'.code.toByte(), 'n'.code.toByte()) +
            "hello".toByteArray(Charsets.UTF_8)
        assertEquals("hello", NfcNdefCodec.decodeTextPayload(payload))
    }

    @Test fun `RTD_TEXT payload handles empty`() {
        assertEquals("", NfcNdefCodec.decodeTextPayload(ByteArray(0)))
    }

    @Test fun `RTD_URI payload expands the prefix table`() {
        // prefix index 4 = "https://"
        val payload = byteArrayOf(0x04) + "example.com".toByteArray(Charsets.UTF_8)
        assertEquals("https://example.com", NfcNdefCodec.decodeUriPayload(payload))
    }

    @Test fun `RTD_URI payload with no prefix`() {
        // prefix index 0 = no prefix
        val payload = byteArrayOf(0x00) + "custom:scheme".toByteArray(Charsets.UTF_8)
        assertEquals("custom:scheme", NfcNdefCodec.decodeUriPayload(payload))
    }

    @Test fun `RTD_URI payload handles empty`() {
        assertEquals("", NfcNdefCodec.decodeUriPayload(ByteArray(0)))
    }
}
