package me.rerere.rikkahub.voiceagent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesToolResponseHashTest {
    @Test
    fun `normalizes whitespace before hashing`() {
        assertEquals("alpha beta", HermesToolResponseHash.normalize(" \nalpha\t  beta\r\n"))
        assertEquals(
            "1a989ea86150171c687b0727f218eedbb94c4665a7da9b0add1bf5de607f2bf1",
            HermesToolResponseHash.sha256HexNormalized(" \nalpha\t  beta\r\n"),
        )
    }

    @Test
    fun `compares expected hash case insensitively after trimming`() {
        val result = HermesToolResponseHash.calculate(
            answer = "alpha beta",
            expectedSha256 = "  1A989EA86150171C687B0727F218EEDBB94C4665A7DA9B0ADD1BF5DE607F2BF1  ",
        )

        assertEquals("1a989ea86150171c687b0727f218eedbb94c4665a7da9b0add1bf5de607f2bf1", result.sha256Hex)
        assertEquals(10, result.normalizedChars)
        assertEquals(true, result.expectedHashMatch)
    }

    @Test
    fun `leaves match null when expected hash is blank`() {
        val result = HermesToolResponseHash.calculate(answer = "alpha beta", expectedSha256 = " ")

        assertEquals("1a989ea86150171c687b0727f218eedbb94c4665a7da9b0add1bf5de607f2bf1", result.sha256Hex)
        assertNull(result.expectedHashMatch)
    }

    @Test
    fun `diagnostic detail includes only safe response metadata`() {
        val detail = HermesToolResponseHash.diagnosticDetail(
            callId = "call-redacted",
            answer = "private secret answer",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            elapsedMs = 123,
            serverElapsedMs = 45,
        )

        assertTrue(detail.contains("callId=call-redacted"))
        assertTrue(detail.contains("responseChars=21"))
        assertTrue(detail.contains("normalizedChars=21"))
        assertTrue(detail.contains("actualHash="))
        assertTrue(detail.contains("expectedHashMatch=false"))
        assertTrue(detail.contains("elapsedMs=123"))
        assertTrue(detail.contains("serverElapsedMs=45"))
        assertFalse(detail.contains("private"))
        assertFalse(detail.contains("secret"))
        assertFalse(detail.contains("answer"))
    }
}
