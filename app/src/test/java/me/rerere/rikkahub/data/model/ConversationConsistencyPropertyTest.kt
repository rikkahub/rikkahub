package me.rerere.rikkahub.data.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * PROPERTY #6 — CONSISTENCY structural invariants over [Conversation] / [MessageNode].
 *
 * selectIndex is generated STRICTLY IN-RANGE because neither [Conversation.currentMessages]
 * (Conversation.kt:44-47, indexes node.messages[node.selectIndex] directly) nor
 * [MessageNode.currentMessage] (Conversation.kt:114, throws IllegalStateException for out-of-range)
 * coerces an out-of-range index. The documented contract is selectIndex in messages.indices; an
 * out-of-range index would test a documented exception, which is a separate negative test (pinned by
 * the last @Test), not this positive invariant.
 */
class ConversationConsistencyPropertyTest {

    private val arbKotlinUuid: Arb<Uuid> = Arb.uuid().map { Uuid.parse(it.toString()) }

    private val arbUIMessage: Arb<UIMessage> = arbitrary {
        UIMessage(
            id = arbKotlinUuid.bind(),
            role = Arb.enum<MessageRole>().bind(),
            // Always at least one Text part so the message is non-trivial.
            parts = listOf(UIMessagePart.Text(Arb.string(0..8).bind())),
        )
    }

    private val arbMessageNode: Arb<MessageNode> = arbitrary {
        val messages = Arb.list(arbUIMessage, 1..4).bind() // >= 1 so currentMessage is valid
        MessageNode(
            id = arbKotlinUuid.bind(),
            messages = messages,
            selectIndex = Arb.int(0 until messages.size).bind(),
        )
    }

    private val arbConversation: Arb<Conversation> = arbitrary {
        Conversation(
            assistantId = arbKotlinUuid.bind(),
            messageNodes = Arb.list(arbMessageNode, 0..5).bind(),
        )
    }

    @Test
    fun `currentMessages selects exactly one message per node`() {
        runBlocking {
            checkAll(200, arbConversation) { conversation ->
                val current = conversation.currentMessages

                // (1) one selected message per node.
                assertEquals(conversation.messageNodes.size, current.size)

                conversation.messageNodes.forEachIndexed { i, node ->
                    // (2) selectIndex in range; currentMessage does not throw and is the selected element.
                    assertTrue("selectIndex out of range", node.selectIndex in node.messages.indices)
                    assertSame(node.messages[node.selectIndex], node.currentMessage)
                    // (3) currentMessages element-wise mirrors the node's selected message.
                    assertSame(node.messages[node.selectIndex], current[i])
                }
            }
        }
    }

    @Test
    fun `currentMessage throws for an out-of-range selectIndex`() {
        // Pins the documented IllegalStateException contract (Conversation.kt:114).
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("a")))),
            selectIndex = 5,
        )
        assertThrows(IllegalStateException::class.java) { node.currentMessage }
    }
}
