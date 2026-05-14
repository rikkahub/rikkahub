package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentDetectorTest {

    private fun cats(text: String) = SensitiveContentDetector.scan(text)

    // ---- positive: clipboard payloads worth warning about ----

    @Test fun `detects JWT shape`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno"
        assertTrue(cats(jwt).contains(SensitiveContentDetector.Category.JWT))
    }

    @Test fun `detects sk- API key`() {
        val k = "sk-abc123def456ghi789jkl012mno345pqr"
        assertTrue(cats(k).contains(SensitiveContentDetector.Category.API_KEY))
    }

    @Test fun `detects GitHub token`() {
        val k = "ghp_aZbYcXdWeVfUgThSiRjQkPlOmNnMlOkPj"
        assertTrue(cats(k).contains(SensitiveContentDetector.Category.API_KEY))
    }

    @Test fun `detects AWS access key id`() {
        val k = "AKIAIOSFODNN7EXAMPLE"
        assertTrue(cats(k).contains(SensitiveContentDetector.Category.API_KEY))
    }

    @Test fun `detects valid Visa number with spaces`() {
        // 4111 1111 1111 1111 — well-known test PAN, passes Luhn.
        val pan = "Card on file: 4111 1111 1111 1111 exp 12/30"
        assertTrue(cats(pan).contains(SensitiveContentDetector.Category.CREDIT_CARD))
    }

    @Test fun `detects email-password pair`() {
        val s = "alice@example.com:hunter2hunter"
        assertTrue(cats(s).contains(SensitiveContentDetector.Category.EMAIL_PASSWORD))
    }

    @Test fun `flags long opaque blob when no specific shape matched`() {
        // 50 chars of base64 alphabet, no JWT dots / sk- prefix / etc.
        val blob = "QWxsIHRoZSB0aHJlc2hvbGQgY2hlY2tzIG11c3QgcGFzcyB0b28="
        val hits = cats(blob)
        assertTrue(hits.contains(SensitiveContentDetector.Category.LONG_OPAQUE_BLOB))
        // Should not also fire JWT / API_KEY / etc.
        assertFalse(hits.contains(SensitiveContentDetector.Category.JWT))
    }

    // ---- negative: false-positive suppression ----

    @Test fun `empty and null are clean`() {
        assertEquals(emptyList<SensitiveContentDetector.Category>(), cats(""))
        assertEquals(emptyList<SensitiveContentDetector.Category>(), SensitiveContentDetector.scan(null))
    }

    @Test fun `short plain text is clean`() {
        assertTrue(cats("hi mom please bring milk").isEmpty())
    }

    @Test fun `long order number that fails Luhn is not flagged as card`() {
        // 16 digits, fails Luhn checksum.
        val ord = "Order #1234567890123456 ready"
        val hits = cats(ord)
        assertFalse(hits.contains(SensitiveContentDetector.Category.CREDIT_CARD))
    }

    @Test fun `JWT does not also fire long opaque blob`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno"
        val hits = cats(jwt)
        assertTrue(hits.contains(SensitiveContentDetector.Category.JWT))
        assertFalse(hits.contains(SensitiveContentDetector.Category.LONG_OPAQUE_BLOB))
    }

    // ---- URL query scan ----

    @Test fun `clean URL has no exfil hits`() {
        val u = "https://example.com/path/to/page?lang=en&id=42"
        assertTrue(SensitiveContentDetector.scanUrlQuery(u).isEmpty())
    }

    @Test fun `URL whose path has long token is not flagged`() {
        // CDN-style asset under PATH (not query) — should not warn.
        val u = "https://cdn.example.com/static/QWxsIHRoZSB0aHJlc2hvbGQgY2hlY2tzIG11c3QgcGFzcyB0b28=/img.png"
        assertTrue(SensitiveContentDetector.scanUrlQuery(u).isEmpty())
    }

    @Test fun `URL with long base64 in query is flagged`() {
        val u = "https://evil.example.com/log?data=QWxsIHRoZSB0aHJlc2hvbGQgY2hlY2tzIG11c3QgcGFzcyB0b28="
        assertTrue(SensitiveContentDetector.scanUrlQuery(u).isNotEmpty())
    }

    @Test fun `URL with JWT in query is flagged`() {
        val u = "https://e.test/cb?token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno"
        val hits = SensitiveContentDetector.scanUrlQuery(u)
        assertTrue(hits.contains(SensitiveContentDetector.Category.JWT))
    }

    @Test fun `URL query that looks like card number is flagged`() {
        val u = "https://e.test/checkout?pan=4111111111111111"
        val hits = SensitiveContentDetector.scanUrlQuery(u)
        assertTrue(hits.contains(SensitiveContentDetector.Category.CREDIT_CARD))
    }

    @Test fun `URL with no query is clean`() {
        assertTrue(SensitiveContentDetector.scanUrlQuery("https://example.com/foo/bar").isEmpty())
    }
}
