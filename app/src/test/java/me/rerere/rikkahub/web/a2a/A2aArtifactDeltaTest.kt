package me.rerere.rikkahub.web.a2a

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.web.dto.ConversationDto
import me.rerere.rikkahub.web.dto.MessageDto
import me.rerere.rikkahub.web.dto.MessageNodeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class A2aArtifactDeltaTest {

    @Test
    fun `text growth by prefix emits append delta`() {
        val nodeId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val previous = conversationDto(nodeId, "hello")
        val current = conversationDto(nodeId, "hello world")

        val delta = classifyA2aArtifactDelta(
            previousConversationDto = previous,
            currentDto = current,
            lastSentTextByNode = mapOf(nodeId to "hello"),
        )

        assertEquals(nodeId, delta?.nodeId)
        assertEquals(" world", delta?.text)
        assertEquals("hello world", delta?.fullText)
        assertEquals(true, delta?.append)
    }

    @Test
    fun `non prefix text emits snapshot artifact`() {
        val nodeId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val previous = conversationDto(nodeId, "hello")
        val current = conversationDto(nodeId, "replacement")

        val delta = classifyA2aArtifactDelta(
            previousConversationDto = previous,
            currentDto = current,
            lastSentTextByNode = mapOf(nodeId to "hello"),
        )

        assertEquals("replacement", delta?.text)
        assertEquals("replacement", delta?.fullText)
        assertEquals(false, delta?.append)
    }

    @Test
    fun `structural changes do not emit single node artifact deltas`() {
        val previous = conversationDto(Uuid.parse("11111111-1111-1111-1111-111111111111"), "hello")
        val current = previous.copy(title = "changed")

        assertNull(
            classifyA2aArtifactDelta(
                previousConversationDto = previous,
                currentDto = current,
                lastSentTextByNode = emptyMap(),
            )
        )
    }

    private fun conversationDto(nodeId: Uuid, text: String) = ConversationDto(
        id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        assistantId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        title = "",
        messages = listOf(
            MessageNodeDto(
                id = nodeId.toString(),
                messages = listOf(
                    MessageDto(
                        id = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                        role = MessageRole.ASSISTANT.name,
                        parts = listOf(UIMessagePart.Text(text)),
                        createdAt = "2026-01-01T00:00:00",
                    )
                ),
                selectIndex = 0,
            )
        ),
        chatSuggestions = emptyList(),
        isPinned = false,
        createAt = 1,
        updateAt = 2,
    )
}
