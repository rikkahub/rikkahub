package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class QueuedAttachmentCleanupTest {
    private val image = UIMessagePart.Image("file:///upload/image.png")
    private val video = UIMessagePart.Video("file:///upload/video.mp4")

    @Test
    fun `revoking a message cleans only local attachments and deduplicates URLs`() {
        val queue = MessageQueue()
        queue.enqueue(
            listOf(
                image, image, video,
                UIMessagePart.Audio("file:///upload/audio.mp3"),
                UIMessagePart.Document("file:///upload/document.pdf", "document.pdf"),
                UIMessagePart.Image("https://example.com/image.png"),
                UIMessagePart.Image("content://photos/image"),
                UIMessagePart.Image("data:image/png;base64,abc"),
            )
        )

        val removed = queue.remove(queue.state.value.messages.single().id)!!

        assertEquals(
            setOf(image.url, video.url, "file:///upload/audio.mp3", "file:///upload/document.pdf"),
            unreferencedQueuedAttachmentUrls(removed, emptyList(), queue.state.value.messages),
        )
    }

    @Test
    fun `saving edits cleans removed attachments but retains unchanged ones`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(image, video))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)

        val previous = queue.finishEdit(id, listOf(image))!!

        assertEquals(
            setOf(video.url),
            unreferencedQueuedAttachmentUrls(previous, emptyList(), queue.state.value.messages),
        )
    }

    @Test
    fun `cancelled and rejected edits do not return attachments for cleanup`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(image))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)

        assertNull(queue.finishEdit(id, emptyList()))
        assertTrue(queue.state.value.messages.single().isEditing)
        assertNull(queue.finishEdit(id))
        assertEquals(listOf(image), queue.takeNext()!!.parts)
        assertNull(queue.finishEdit(id, listOf(video)))
        assertNull(queue.remove(id))
    }

    @Test
    fun `shared queued attachments are retained until the last reference is revoked`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(image, video))
        queue.enqueue(listOf(image))
        val first = queue.remove(queue.state.value.messages.first().id)!!

        assertEquals(
            setOf(video.url),
            unreferencedQueuedAttachmentUrls(first, emptyList(), queue.state.value.messages),
        )
        val last = queue.remove(queue.state.value.messages.single().id)!!
        assertEquals(
            setOf(image.url),
            unreferencedQueuedAttachmentUrls(last, emptyList(), emptyList())
        )
    }

    @Test
    fun `unselected history branches and nested tool results retain attachments`() {
        val previous = QueuedMessage(parts = listOf(image, video))
        val historicalMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "outer",
                    toolName = "tool",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "inner",
                            toolName = "tool",
                            input = "{}",
                            output = listOf(image),
                        )
                    ),
                )
            ),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(historicalMessage, UIMessage.user("selected branch")),
                    selectIndex = 1,
                )
            ),
        )

        assertEquals(
            setOf(video.url),
            unreferencedQueuedAttachmentUrls(previous, listOf(conversation), emptyList()),
        )
    }

    @Test
    fun `a dequeued message retains attachments before history is saved`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(image))
        val submitting = queue.takeNext()!!
        val removed = QueuedMessage(parts = listOf(image, video))

        assertTrue(queue.state.value.messages.isEmpty())
        assertEquals(
            setOf(video.url),
            unreferencedQueuedAttachmentUrls(removed, emptyList(), listOf(submitting)),
        )
    }

    @Test
    fun `text-only edits keep all attachment files`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("original"), image))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        val previous = queue.finishEdit(id, listOf(UIMessagePart.Text("edited"), image))!!

        assertTrue(
            unreferencedQueuedAttachmentUrls(
                previous,
                emptyList(),
                queue.state.value.messages
            ).isEmpty()
        )
    }
}
