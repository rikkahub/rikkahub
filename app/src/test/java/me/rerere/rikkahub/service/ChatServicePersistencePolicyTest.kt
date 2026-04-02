package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServicePersistencePolicyTest {
    @Test
    fun `skip persistence for empty unsaved conversations`() {
        val conversation = createConversation()

        assertTrue(shouldSkipConversationPersistence(exists = false, conversation = conversation))
    }

    @Test
    fun `do not skip persistence for empty saved conversations`() {
        val conversation = createConversation()

        assertFalse(shouldSkipConversationPersistence(exists = true, conversation = conversation))
    }

    @Test
    fun `skip persistence for temporary conversations even with messages`() {
        val conversation = createConversation(
            messageNodes = listOf(UIMessage.user("hello").toMessageNode()),
            isTemporaryConversation = true,
        )

        assertTrue(shouldSkipConversationPersistence(exists = false, conversation = conversation))
        assertTrue(shouldSkipConversationPersistence(exists = true, conversation = conversation))
    }

    @Test
    fun `persist regular conversations with messages`() {
        val conversation = createConversation(
            messageNodes = listOf(UIMessage.user("hello").toMessageNode()),
        )

        assertFalse(shouldSkipConversationPersistence(exists = false, conversation = conversation))
    }

    private fun createConversation(
        messageNodes: List<me.rerere.rikkahub.data.model.MessageNode> = emptyList(),
        isTemporaryConversation: Boolean = false,
    ): Conversation {
        return Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = messageNodes,
            isTemporaryConversation = isTemporaryConversation,
        )
    }
}
