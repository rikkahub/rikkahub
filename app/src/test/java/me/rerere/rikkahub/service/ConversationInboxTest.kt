package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 只关心用户输入的测试用：下一轮是发送用户输入时返回它 */
internal fun ConversationInbox.nextMessage(): QueuedMessage? = (takeNext() as? InboxTurn.Message)?.message

internal fun ConversationInbox.removeMessage(id: Uuid): QueuedMessage? = remove(id) as QueuedMessage?

class ConversationInboxTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private fun notice(name: String) = UIMessagePart.Notice(title = name, text = "<notice>$name</notice>")

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.nextMessage())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.nextMessage()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.nextMessage()!!.parts)
        val second = queue.nextMessage()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.nextMessage())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.nextMessage())
        queue.finishEdit(id, text("edited"))

        val first = queue.nextMessage()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.nextMessage()!!.parts)
        assertNull(queue.nextMessage())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = ConversationInbox()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.nextMessage()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.removeMessage(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.nextMessage()!!.parts)
        assertNull(queue.nextMessage())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.nextMessage())
        queue.resume()
        assertEquals(text("edited"), queue.nextMessage()!!.parts)
        assertEquals(text("second"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        val id = queue.nextMessage()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.nextMessage())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.nextMessage()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = ConversationInbox()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = ConversationInbox()
        val second = ConversationInbox()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.nextMessage())
        assertEquals(text("second"), second.nextMessage()!!.parts)
    }

    @Test
    fun `notices start a turn only when no user input is waiting`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        inbox.enqueue(text("question"))

        assertEquals(text("question"), (inbox.takeNext() as InboxTurn.Message).message.parts)
        assertEquals(InboxTurn.Notices, inbox.takeNext())
        // 开始一轮不会取走通知，由那一轮自己取
        assertEquals(listOf(notice("a")), inbox.takeNotices())
        assertNull(inbox.takeNext())
    }

    @Test
    fun `pausing holds back notice turns but a running turn can still take them`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        inbox.pause()

        assertNull(inbox.takeNext())
        assertEquals(listOf(notice("a")), inbox.takeNotices())
        assertTrue(inbox.state.value.items.isEmpty())
    }

    @Test
    fun `an edited head blocks later input but not notices`() {
        val inbox = ConversationInbox()
        inbox.enqueue(text("first"))
        inbox.beginEdit(inbox.state.value.messages.single().id)
        inbox.post("a", notice("a"))

        assertEquals(InboxTurn.Notices, inbox.takeNext())
    }

    @Test
    fun `withdrawing removes only the unsent notice with that key`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        inbox.post("b", notice("b"))
        inbox.withdraw("a")
        inbox.withdraw("missing")

        assertEquals(listOf(notice("b")), inbox.takeNotices())
    }

    @Test
    fun `posting the same key again replaces the notice in place`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("old"))
        inbox.enqueue(text("question"))
        inbox.post("a", notice("new"))

        assertEquals(listOf("a", null), inbox.state.value.items.map { (it as? QueuedNotice)?.key })
        assertEquals(listOf(notice("new")), inbox.takeNotices())
    }

    @Test
    fun `removing a notice drops it`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        val removed = inbox.remove(inbox.state.value.items.single().id)

        assertTrue(removed is QueuedNotice)
        assertTrue(inbox.takeNotices().isEmpty())
    }
}
