package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ThinkTagTransformerTest {
    @Test
    fun `should parse think tag when no visual regex blocks it`() {
        val assistant = Assistant()

        assertTrue(
            shouldParseThinkTagAfterVisualRegex(
                text = "<think>inner</think>answer",
                assistant = assistant,
                messageDepthFromEnd = 1,
                enableThinkRegex = true,
            )
        )
    }

    @Test
    fun `visual only regex should take priority over think tag parsing`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "</?think>",
                    replaceString = "",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                )
            )
        )

        assertFalse(
            shouldParseThinkTagAfterVisualRegex(
                text = "<think>inner</think>answer",
                assistant = assistant,
                messageDepthFromEnd = 1,
                enableThinkRegex = true,
            )
        )
    }

    @Test
    fun `visual only regex with unmatched depth should not block think parsing`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "</?think>",
                    replaceString = "",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                    minDepth = 2,
                )
            )
        )

        assertTrue(
            shouldParseThinkTagAfterVisualRegex(
                text = "<think>inner</think>answer",
                assistant = assistant,
                messageDepthFromEnd = 1,
                enableThinkRegex = true,
            )
        )
    }

    @Test
    fun `disabled think regex switch should bypass visual regex gating`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "</?think>",
                    replaceString = "",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                )
            )
        )

        assertTrue(
            shouldParseThinkTagAfterVisualRegex(
                text = "<think>inner</think>answer",
                assistant = assistant,
                messageDepthFromEnd = 1,
                enableThinkRegex = false,
            )
        )
    }
}
