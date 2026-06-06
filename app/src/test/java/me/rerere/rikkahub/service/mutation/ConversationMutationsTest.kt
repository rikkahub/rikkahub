package me.rerere.rikkahub.service.mutation

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pure-JVM tests for [ConversationMutations], the message-mutation core extracted out of
 * ChatService. Fixtures are built directly (no Android / Robolectric / Context) and pin the exact
 * invariants the design proposal lists: delete branch-keep / node-removal / missing→null,
 * selectMessageNode valid-change / invalid-index / unknown-node / same-index no-op, updateTranslation
 * set + clear + isolation, editMessage append + selectIndex + missing→null, forkAtMessage subList
 * through target + id regeneration + metadata preservation + copyPart seam + missing→NotFoundException.
 */
class ConversationMutationsTest {

    private fun msg(role: MessageRole = MessageRole.USER, text: String = "t") = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun conversationOf(vararg nodes: MessageNode) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messageNodes = nodes.toList(),
    )

    // ---- deleteMessage ----

    @Test
    fun `deleteMessage drops the branch message but keeps the node and coerces selectIndex`() {
        val keep = msg(text = "keep")
        val drop = msg(text = "drop")
        val node = MessageNode(messages = listOf(keep, drop), selectIndex = 1)
        val conversation = conversationOf(node)

        val result = ConversationMutations.deleteMessage(conversation, drop.id)!!

        val updated = result.messageNodes.single()
        assertEquals(listOf(keep.id), updated.messages.map { it.id })
        assertEquals(0, updated.selectIndex)
    }

    @Test
    fun `deleteMessage removes the whole node when it becomes empty`() {
        val only = msg(text = "only")
        val survivor = msg(text = "survivor")
        val conversation = conversationOf(
            MessageNode(messages = listOf(only)),
            MessageNode(messages = listOf(survivor)),
        )

        val result = ConversationMutations.deleteMessage(conversation, only.id)!!

        assertEquals(listOf(survivor.id), result.messageNodes.map { it.messages.single().id })
    }

    @Test
    fun `deleteMessage returns null when messageId is absent`() {
        val conversation = conversationOf(MessageNode(messages = listOf(msg())))

        assertNull(ConversationMutations.deleteMessage(conversation, Uuid.random()))
    }

    // ---- selectMessageNode ----

    @Test
    fun `selectMessageNode changes selectIndex for a valid index`() {
        val a = msg(text = "a")
        val b = msg(text = "b")
        val node = MessageNode(messages = listOf(a, b), selectIndex = 0)
        val conversation = conversationOf(node)

        val result = ConversationMutations.selectMessageNode(conversation, node.id, 1)

        assertEquals(1, result.messageNodes.single().selectIndex)
    }

    @Test(expected = BadRequestException::class)
    fun `selectMessageNode throws BadRequestException for an out-of-range index`() {
        val node = MessageNode(messages = listOf(msg()), selectIndex = 0)
        ConversationMutations.selectMessageNode(conversationOf(node), node.id, 5)
    }

    @Test(expected = NotFoundException::class)
    fun `selectMessageNode throws NotFoundException for an unknown nodeId`() {
        val node = MessageNode(messages = listOf(msg()), selectIndex = 0)
        ConversationMutations.selectMessageNode(conversationOf(node), Uuid.random(), 0)
    }

    @Test
    fun `selectMessageNode returns the unchanged conversation for a same-index call`() {
        val node = MessageNode(messages = listOf(msg(), msg(text = "b")), selectIndex = 1)
        val conversation = conversationOf(node)

        val result = ConversationMutations.selectMessageNode(conversation, node.id, 1)

        assertSame(conversation, result)
    }

    // ---- updateTranslation ----

    @Test
    fun `updateTranslation sets translation on the right message only`() {
        val target = msg(text = "target")
        val other = msg(text = "other")
        val conversation = conversationOf(
            MessageNode(messages = listOf(target, other), selectIndex = 0),
        )

        val result = ConversationMutations.updateTranslation(conversation, target.id, "hola")

        val messages = result.messageNodes.single().messages
        assertEquals("hola", messages.first { it.id == target.id }.translation)
        assertNull(messages.first { it.id == other.id }.translation)
    }

    @Test
    fun `updateTranslation clears translation when text is null`() {
        val target = msg(text = "target").copy(translation = "hola")
        val conversation = conversationOf(MessageNode(messages = listOf(target)))

        val result = ConversationMutations.updateTranslation(conversation, target.id, null)

        assertNull(result.messageNodes.single().messages.single().translation)
    }

    // ---- editMessage ----

    @Test
    fun `editMessage appends the new message and points selectIndex at it`() {
        val existing = msg(role = MessageRole.USER, text = "old")
        val node = MessageNode(messages = listOf(existing), selectIndex = 0)
        val conversation = conversationOf(node)
        val newMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("new")))

        val result = ConversationMutations.editMessage(conversation, existing.id, newMessage)!!

        val updated = result.messageNodes.single()
        assertEquals(2, updated.messages.size)
        assertEquals(1, updated.selectIndex)
        // role re-stamped to the node's role (per-node落款 semantics preserved)
        assertEquals(MessageRole.USER, updated.messages[1].role)
        assertEquals("new", (updated.messages[1].parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `editMessage returns null when messageId is absent`() {
        val conversation = conversationOf(MessageNode(messages = listOf(msg())))
        val newMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("x")))

        assertNull(ConversationMutations.editMessage(conversation, Uuid.random(), newMessage))
    }

    // ---- forkAtMessage ----

    @Test
    fun `forkAtMessage copies through target node drops the rest and regenerates ids`() {
        val n0 = MessageNode(messages = listOf(msg(text = "n0")))
        val target = msg(text = "n1")
        val n1 = MessageNode(messages = listOf(target))
        val n2 = MessageNode(messages = listOf(msg(text = "n2")))
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(n0, n1, n2),
            customSystemPrompt = "sys",
            modeInjectionIds = setOf(Uuid.random()),
            lorebookIds = setOf(Uuid.random()),
        )

        val fork = ConversationMutations.forkAtMessage(conversation, target.id)

        // through and including target node only
        assertEquals(2, fork.messageNodes.size)
        // conversation id regenerated
        assertNotEquals(conversation.id, fork.id)
        // every node id regenerated
        assertNotEquals(n0.id, fork.messageNodes[0].id)
        assertNotEquals(n1.id, fork.messageNodes[1].id)
        // metadata preserved
        assertEquals(conversation.assistantId, fork.assistantId)
        assertEquals("sys", fork.customSystemPrompt)
        assertEquals(conversation.modeInjectionIds, fork.modeInjectionIds)
        assertEquals(conversation.lorebookIds, fork.lorebookIds)
    }

    @Test
    fun `forkAtMessage runs copyPart on every part proving the file-copy seam is external`() {
        val target = msg(text = "a")
        val conversation = conversationOf(
            MessageNode(messages = listOf(target, msg(text = "b"))),
        )
        val seen = mutableListOf<UIMessagePart>()

        val fork = ConversationMutations.forkAtMessage(conversation, target.id) { part ->
            seen += part
            if (part is UIMessagePart.Text) part.copy(text = part.text + "!") else part
        }

        assertEquals(2, seen.size)
        val texts = fork.messageNodes.single().messages.map {
            (it.parts.single() as UIMessagePart.Text).text
        }
        assertTrue(texts.containsAll(listOf("a!", "b!")))
    }

    @Test(expected = NotFoundException::class)
    fun `forkAtMessage throws NotFoundException for an absent target`() {
        val conversation = conversationOf(MessageNode(messages = listOf(msg())))
        ConversationMutations.forkAtMessage(conversation, Uuid.random())
    }
}
