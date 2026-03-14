package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningLevelTest {
    @Test
    fun `xhigh should clamp to high for openai chat completions`() {
        assertEquals("high", ReasoningLevel.XHIGH.toOpenAIChatCompletionsEffort())
    }
}
