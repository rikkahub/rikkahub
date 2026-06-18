package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.mutation.ConversationMutations
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatAssistantSwitchTest {

    @Test
    fun `switching assistant A to B to A preserves conversation id and message nodes`() {
        val assistantA = Uuid.random()
        val assistantB = Uuid.random()
        val node = MessageNode(
            messages = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"))),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))),
            ),
            selectIndex = 1,
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = assistantA,
            messageNodes = listOf(node),
        )

        val movedToB = ConversationMutations.moveToAssistant(conversation, assistantB)
        val movedBackToA = ConversationMutations.moveToAssistant(movedToB, assistantA)

        assertEquals(assistantB, movedToB.assistantId)
        assertEquals(assistantA, movedBackToA.assistantId)
        assertEquals(conversation.id, movedToB.id)
        assertEquals(conversation.id, movedBackToA.id)
        assertEquals(conversation.messageNodes, movedToB.messageNodes)
        assertEquals(conversation.messageNodes, movedBackToA.messageNodes)
        assertEquals(conversation.copy(assistantId = assistantB), movedToB)
        assertEquals(conversation.copy(assistantId = assistantA), movedBackToA)
    }

    @Test
    fun `rebind folded onto live streaming state preserves in-flight message nodes`() {
        // Regression: the active-session rebind must fold the assistant change onto the LIVE
        // conversation (which may carry an in-flight, partially streamed assistant message), not a
        // stale DB snapshot. moveToAssistant must therefore preserve every message node it is given.
        val assistantA = Uuid.random()
        val assistantB = Uuid.random()
        val liveStreaming = Conversation(
            id = Uuid.random(),
            assistantId = assistantA,
            messageNodes = listOf(
                MessageNode(messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("ask"))))),
                // a still-streaming assistant turn that only exists in live session state, not yet in DB
                MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("partial strea"))))),
            ),
        )

        val rebound = ConversationMutations.moveToAssistant(liveStreaming, assistantB)

        assertEquals(assistantB, rebound.assistantId)
        assertEquals(liveStreaming.id, rebound.id)
        assertEquals(liveStreaming.messageNodes, rebound.messageNodes)
        assertEquals(liveStreaming.copy(assistantId = assistantB), rebound)
    }
}
