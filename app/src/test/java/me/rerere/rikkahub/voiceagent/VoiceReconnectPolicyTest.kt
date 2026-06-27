package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceReconnectPolicyTest {
    @Test
    fun `default policy uses bounded exponential delays`() {
        val policy = VoiceReconnectPolicy(jitterRatio = 0.0, jitterSource = { 0.0 })

        val delays = (1..15).map { attempt ->
            policy.delayMsForAttempt(attempt = attempt, elapsedMs = 0L)
        }

        assertEquals(
            listOf(
                1_000L,
                2_000L,
                4_000L,
                8_000L,
                16_000L,
                32_000L,
                64_000L,
                128_000L,
                256_000L,
                300_000L,
                300_000L,
                300_000L,
                300_000L,
                300_000L,
                300_000L,
            ),
            delays,
        )
        assertNull(policy.delayMsForAttempt(attempt = 16, elapsedMs = 0L))
    }

    @Test
    fun `policy caps delay to remaining elapsed budget`() {
        val policy = VoiceReconnectPolicy(jitterRatio = 0.0, jitterSource = { 0.0 })

        assertEquals(
            250L,
            policy.delayMsForAttempt(
                attempt = 10,
                elapsedMs = policy.maxElapsedMs - 250L,
            ),
        )
        assertNull(policy.delayMsForAttempt(attempt = 1, elapsedMs = policy.maxElapsedMs))
    }

    @Test
    fun `policy applies deterministic jitter and never returns negative delay`() {
        val positiveJitter = VoiceReconnectPolicy(jitterRatio = 0.2, jitterSource = { 1.0 })
        val negativeJitter = VoiceReconnectPolicy(jitterRatio = 0.2, jitterSource = { -1.0 })
        val extremeNegativeJitter = VoiceReconnectPolicy(jitterRatio = 2.0, jitterSource = { -1.0 })

        assertEquals(1_200L, positiveJitter.delayMsForAttempt(attempt = 1, elapsedMs = 0L))
        assertEquals(800L, negativeJitter.delayMsForAttempt(attempt = 1, elapsedMs = 0L))
        assertEquals(0L, extremeNegativeJitter.delayMsForAttempt(attempt = 1, elapsedMs = 0L))
    }
}
