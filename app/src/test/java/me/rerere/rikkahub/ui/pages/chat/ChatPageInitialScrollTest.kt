package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatPageInitialScrollTest {

    @Test
    fun `resolveInitialChatScrollTarget should return null when already initialized`() {
        val target = resolveInitialChatScrollTarget(
            chatListInitialized = true,
            nodeId = null,
            messageNodes = listOf(messageNode())
        )

        assertNull(target)
    }

    @Test
    fun `resolveInitialChatScrollTarget should prefer node target when node exists`() {
        val first = messageNode()
        val second = messageNode()

        val target = resolveInitialChatScrollTarget(
            chatListInitialized = false,
            nodeId = second.id,
            messageNodes = listOf(first, second)
        )

        assertEquals(InitialChatScrollTarget.Node(1), target)
    }

    @Test
    fun `resolveInitialChatScrollTarget should fall back to bottom when node is missing`() {
        val target = resolveInitialChatScrollTarget(
            chatListInitialized = false,
            nodeId = Uuid.random(),
            messageNodes = listOf(messageNode(), messageNode())
        )

        assertEquals(InitialChatScrollTarget.Bottom, target)
    }

    @Test
    fun `requiredItemCount should account for bottom spacer`() {
        val target = InitialChatScrollTarget.Bottom

        assertEquals(4, requiredItemCount(target, messageNodeCount = 3))
    }

    @Test
    fun `requiredItemCount should match node index plus one`() {
        val target = InitialChatScrollTarget.Node(index = 5)

        assertEquals(6, requiredItemCount(target, messageNodeCount = 100))
    }

    @Test
    fun `resolveScrollIndex should scroll to last rendered item for bottom target`() {
        val scrollIndex = resolveScrollIndex(
            target = InitialChatScrollTarget.Bottom,
            totalItemCount = 8
        )

        assertEquals(7, scrollIndex)
    }

    @Test
    fun `resolveScrollIndex should keep message index for node target`() {
        val scrollIndex = resolveScrollIndex(
            target = InitialChatScrollTarget.Node(index = 3),
            totalItemCount = 20
        )

        assertEquals(3, scrollIndex)
    }

    @Test
    fun `resolveInitialChatScrollTarget should return null for empty conversation`() {
        val target = resolveInitialChatScrollTarget(
            chatListInitialized = false,
            nodeId = null,
            messageNodes = emptyList()
        )

        assertNull(target)
    }

    private fun messageNode(): MessageNode {
        return MessageNode.of(UIMessage.user("hello"))
    }
}
