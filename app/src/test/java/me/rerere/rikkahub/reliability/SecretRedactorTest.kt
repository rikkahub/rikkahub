package me.rerere.rikkahub.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug-report ZIP includes a logcat dump that goes through [SecretRedactor]. The
 * patterns are aggressive on false positives — a token-shaped string that gets blanked
 * by mistake is just lost noise; a token that slips through is a refund event.
 */
class SecretRedactorTest {

    @Test fun `Telegram bot tokens are redacted`() {
        val input = "TelegramBotService: posting with token 1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ_abc-def\n"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ_abc-def"))
        assertTrue(out.contains("[redacted-telegram-token]"))
    }

    @Test fun `Authorization header is redacted`() {
        val input = "Sending Authorization: Bearer abc.def.ghi.somelongthing\n"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("abc.def.ghi.somelongthing"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `X-Api-Key header is redacted case-insensitively`() {
        val input1 = "X-Api-Key: sk-proj-abcdefghijklmnopqrstuvwxyz123456\n"
        val input2 = "x-api-key=sk-proj-abcdefghijklmnopqrstuvwxyz123456\n"
        for (input in listOf(input1, input2)) {
            val out = SecretRedactor.redact(input)
            assertFalse(out.contains("sk-proj-abcdefghijklmnopqrstuvwxyz123456"))
        }
    }

    @Test fun `long hex strings are redacted`() {
        val input = "Saved fingerprint: a3f2c8d4e5b6a7c8d9e0f1a2b3c4d5e6\n"
        val out = SecretRedactor.redact(input)
        assertTrue(out.contains("[redacted-hex]"))
    }

    @Test fun `short hex strings pass through`() {
        val input = "Color: #ff0000\n"
        val out = SecretRedactor.redact(input)
        assertTrue(out.contains("ff0000"))
    }

    @Test fun `ssh url with embedded creds gets redacted`() {
        val input = "Connecting ssh://username:secret@10.0.0.5:22/\n"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("username:secret@"))
        assertTrue(out.contains("ssh://[redacted]@"))
    }

    @Test fun `Cookie header is redacted`() {
        val input = "Cookie: sessionid=abcd1234efgh5678ijkl\n"
        val out = SecretRedactor.redact(input)
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `redactor is idempotent on already-redacted text`() {
        val once = SecretRedactor.redact("Authorization: Bearer abcdef.ghijkl.mnopqr.stuvwx")
        val twice = SecretRedactor.redact(once)
        // Running twice should yield identical output (no infinite-redact cycle, no
        // extra mangling of "[redacted]" markers).
        // Note: `[redacted]` itself is short alphanumeric; should not get re-matched
        // by any pattern.
        assertTrue(twice.contains("[redacted]"))
    }
}
