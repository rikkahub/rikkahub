package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationContextTokensTest {
    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    private fun at(minute: Int) = LocalDateTime(2026, 1, 1, 0, minute)

    private fun assistantMessage(
        minute: Int,
        usage: TokenUsage? = null,
        contextTokens: Int? = null,
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        createdAt = at(minute),
        usage = usage,
        contextTokens = contextTokens,
    )

    @Test
    fun `uses context tokens of the last reply instead of accumulated usage`() {
        val conversation = conversation(
            UIMessage.user("hi"),
            assistantMessage(1, usage = TokenUsage(promptTokens = 15_000, completionTokens = 140), contextTokens = 7_600),
        )

        assertEquals(7_600, conversation.lastContextTokens())
    }

    @Test
    fun `falls back to usage for messages without context tokens`() {
        val conversation = conversation(
            assistantMessage(1, usage = TokenUsage(promptTokens = 7_000, completionTokens = 100)),
        )

        assertEquals(7_100, conversation.lastContextTokens())
    }

    @Test
    fun `returns null when compressed after the last reply`() {
        val conversation = conversation(
            assistantMessage(1, contextTokens = 7_600),
            UIMessage.user("summary").copy(createdAt = at(2), isContextCheckpoint = true),
        )

        assertNull(conversation.lastContextTokens())
    }

    @Test
    fun `reply after the checkpoint is used`() {
        val conversation = conversation(
            UIMessage.user("summary").copy(createdAt = at(1), isContextCheckpoint = true),
            UIMessage.user("hi").copy(createdAt = at(2)),
            assistantMessage(3, contextTokens = 2_000),
        )

        assertEquals(2_000, conversation.lastContextTokens())
    }

    @Test
    fun `returns null without any reply`() {
        assertNull(conversation(UIMessage.user("hi")).lastContextTokens())
    }
}
