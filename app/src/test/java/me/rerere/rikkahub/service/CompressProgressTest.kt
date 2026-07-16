package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CompressProgressTest {
    @Test
    fun fraction_usesFixed1500TokenCompletionPoint() {
        fun progress(tokens: Int) = ChatService.CompressProgress(
            outputTokens = tokens,
            targetTokens = 4000,
            completedChunks = 0,
            totalChunks = 1,
        )

        assertEquals(1500, progress(0).progressMaxTokens)
        assertEquals(0.5f, progress(750).fraction, 0.0001f)
        assertEquals(1f, progress(1500).fraction, 0.0001f)
        assertEquals(1f, progress(1800).fraction, 0.0001f)
    }
}
