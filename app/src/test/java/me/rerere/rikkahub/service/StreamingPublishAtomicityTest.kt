package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression test for issue #108's lost-update race in the streaming UI publish path.
 *
 * Two coroutines on different dispatchers mutate the same per-session `MutableStateFlow<Conversation>`:
 *  - the streaming publish (appScope, background) merges each accumulated chunk's messages, and
 *  - a UI write (viewModelScope, main) flips a non-message field such as the @Transient
 *    [MessageNode.isFavorite] (ChatVM.toggleMessageFavorite -> ChatService.updateConversationState).
 *
 * The original publish did a NON-ATOMIC read-modify-write —
 *   `getConversationFlow(id).value.updateCurrentMessages(messages)` then `state.value = merged` —
 * so a favorite landing between that read and that write was overwritten by the stale snapshot
 * (TOCTOU lost update). The fix collapses the publish into a single CAS via
 * [publishStreamingMessages] (`state.update { it.updateCurrentMessages(messages) }`), which re-applies
 * the merge onto the LATEST value; because [Conversation.updateCurrentMessages] rebuilds each node via
 * `node.copy(messages=..., selectIndex=...)` it preserves the non-message field, so last-writer-wins is
 * safe and the favorite is never lost.
 *
 * Both tests drive the PRODUCTION seam [publishStreamingMessages] (not a hand-copied mirror): reverting
 * its body to the old read-then-`.value=` pair reddens [favorite survives a publish racing a toggle...].
 */
class StreamingPublishAtomicityTest {

    private fun assistantMessage(id: Uuid, text: String): UIMessage = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun conversationWith(node: MessageNode): Conversation = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messageNodes = listOf(node),
    )

    /**
     * Test A — the merge-onto-current invariant that makes last-writer-wins safe.
     *
     * Publishing an updated message for an existing node (a) preserves that node's @Transient
     * [MessageNode.isFavorite] and (b) merges the message into the node by index, matching the
     * existing message by id (the documented by-INDEX / by-id semantics of
     * [Conversation.updateCurrentMessages]). This is the load-bearing property behind the fix: if the
     * merge did NOT carry isFavorite forward, no amount of CAS would save it.
     */
    @Test
    fun `publish merges onto current node preserving its favorite flag`() {
        val messageId = Uuid.random()
        val node = MessageNode(
            messages = listOf(assistantMessage(messageId, "partial")),
            selectIndex = 0,
            isFavorite = true,
        )
        val state = MutableStateFlow(conversationWith(node))

        publishStreamingMessages(state, listOf(assistantMessage(messageId, "partial + more")))

        val resultNode = state.value.messageNodes.single()
        assertTrue("merge-onto-current must preserve the @Transient isFavorite", resultNode.isFavorite)
        assertEquals(
            "the updated message must be merged into the node by index/id",
            "partial + more",
            (resultNode.messages.single().parts.single() as UIMessagePart.Text).text,
        )
        assertEquals("the message id must be unchanged", messageId, resultNode.messages.single().id)
    }

    /**
     * Test B — the lost-update regression. A publish writer in a tight loop (the production
     * [publishStreamingMessages] path) races a single favorite-toggle writer (an atomic
     * `state.update {}` flipping the @Transient isFavorite, modelling updateConversationState's CAS),
     * gated by a [CountDownLatch] and repeated over many high-contention trials. On the fixed code the
     * favorite is never lost; reverting [publishStreamingMessages] to the non-atomic read-then-`.value=`
     * pair drops the favorite in the contention window and reddens the per-trial assertion.
     *
     * Mirrors the real-thread idiom of McpManagerConcurrencyTest (daemon threads + a bounded await
     * timeout) so CI can never hang.
     */
    @Test
    fun `favorite survives a publish racing a toggle under high contention`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        val trials = 200
        val publishIterations = 2_000

        repeat(trials) { trial ->
            val messageId = Uuid.random()
            val node = MessageNode(
                messages = listOf(assistantMessage(messageId, "0")),
                selectIndex = 0,
                isFavorite = false,
            )
            val state = MutableStateFlow(conversationWith(node))

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            val publisher = Thread {
                try {
                    start.await()
                    repeat(publishIterations) { i ->
                        // Production publish path: a fresh accumulated message for the same node index/id.
                        publishStreamingMessages(state, listOf(assistantMessage(messageId, i.toString())))
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true }

            val favoriter = Thread {
                try {
                    start.await()
                    // Models updateConversationState's atomic CAS flipping the non-message field exactly
                    // once mid-burst.
                    state.update { current ->
                        current.copy(
                            messageNodes = current.messageNodes.map { n -> n.copy(isFavorite = true) },
                        )
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true }

            publisher.start()
            favoriter.start()
            start.countDown()
            assertTrue(
                "trial $trial: writers did not finish in time",
                done.await(30, TimeUnit.SECONDS),
            )
            publisher.join()
            favoriter.join()

            assertTrue(
                "trial $trial: the favorite flag was lost by a racing streaming publish",
                state.value.messageNodes.single().isFavorite,
            )
        }

        assertTrue(
            "concurrent writers threw: ${errors.joinToString { it.javaClass.name + ": " + it.message }}",
            errors.isEmpty(),
        )
    }
}
