package me.rerere.rikkahub.data.event

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatHistoryBridgeTest {
    @Test
    fun to_chat_history_snapshot_exports_current_messages_and_swipes() {
        val userNodeId = Uuid.random()
        val assistantNodeId = Uuid.random()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(
                    id = userNodeId,
                    messages = listOf(UIMessage.user("hello"))
                ),
                MessageNode(
                    id = assistantNodeId,
                    messages = listOf(
                        UIMessage.assistant("draft"),
                        UIMessage.assistant("final")
                    ),
                    selectIndex = 1,
                ),
            ),
        )

        val snapshot = conversation.toChatHistorySnapshot(
            userName = "Alice",
            assistantName = "Bot",
        )

        assertEquals(conversation.id.toString(), snapshot.conversationId)
        assertEquals("Alice", snapshot.userName)
        assertEquals("Bot", snapshot.assistantName)
        assertEquals(2, snapshot.messages.size)
        assertEquals("Alice", snapshot.messages[0].name)
        assertEquals("hello", snapshot.messages[0].text)
        assertEquals(userNodeId.toString(), snapshot.messages[0].nodeId)
        assertEquals("Bot", snapshot.messages[1].name)
        assertEquals("final", snapshot.messages[1].text)
        assertEquals(1, snapshot.messages[1].swipeIndex)
        assertEquals(listOf("draft", "final"), snapshot.messages[1].swipes)
        assertEquals(assistantNodeId.toString(), snapshot.messages[1].nodeId)
    }

    @Test
    fun to_chat_history_snapshot_flattens_all_text_parts() {
        val image = UIMessagePart.Image(url = "file:///tmp/image.png")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(
                    id = Uuid.random(),
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Text(text = "before"),
                                image,
                                UIMessagePart.Text(text = "after"),
                            ),
                        )
                    ),
                )
            ),
        )

        val snapshot = conversation.toChatHistorySnapshot()

        assertEquals(1, snapshot.messages.size)
        assertEquals("before\nafter", snapshot.messages[0].text)
        assertEquals(listOf("before\nafter"), snapshot.messages[0].swipes)
    }

    @Test
    fun replace_history_text_replaces_all_text_parts_and_preserves_non_text_parts() {
        val image = UIMessagePart.Image(url = "file:///tmp/image.png")
        val leadingText = UIMessagePart.Text(text = "before")
        val trailingText = UIMessagePart.Text(text = "after")
        val updated = listOf(image, leadingText, trailingText).replaceHistoryText("patched")

        assertEquals(2, updated.size)
        assertEquals(image, updated[0])
        assertTrue(updated[1] is UIMessagePart.Text)
        assertEquals("patched", (updated[1] as UIMessagePart.Text).text)
    }

    @Test
    fun replace_history_text_appends_text_when_missing() {
        val image = UIMessagePart.Image(url = "file:///tmp/image.png")
        val updated = listOf(image).replaceHistoryText("new text")

        assertEquals(2, updated.size)
        assertEquals(image, updated[0])
        assertEquals("new text", (updated[1] as UIMessagePart.Text).text)
    }
}
