package me.rerere.rikkahub.data.db.fts

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Issue #122 (bullet 3): a lightweight FTS consistency check needs a baseline "how many rows SHOULD
 * be indexed" that cannot disagree with what the indexer actually inserts. The risk is exactly this
 * kind of drift: if the consistency baseline counted rows with a slightly different blank/extraction
 * rule than [reindexConversationFts]'s INSERT guard, every healthy index would look "inconsistent"
 * (or worse, real drift would be hidden).
 *
 * The unit under test is the pure helper [expectedFtsRowCount], which MUST share the identical
 * extraction + non-blank predicate the indexer uses. The native `message_fts` COUNT(*) path
 * ([MessageFtsManager.indexedRowCount]) is intentionally out of unit scope: it needs the native
 * jieba/simple_snippet FTS5 extensions unavailable in this JVM source set (same documented
 * constraint as [MessageFtsTransactionTest]). Correctness of the DB side is preserved by reusing the
 * same predicate, which this test pins.
 *
 * On the UNFIXED tree this class does not compile: [expectedFtsRowCount] does not exist.
 */
class MessageFtsConsistencyTest {

    @Test
    fun `empty conversation list expects zero rows`() {
        assertEquals(0, expectedFtsRowCount(emptyList()))
    }

    @Test
    fun `two non-blank text messages expect two rows`() {
        val nodes = listOf(
            MessageNode.of(UIMessage.user("hello world")),
            MessageNode.of(UIMessage.assistant("goodbye world")),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), messages = nodes)
        assertEquals(2, expectedFtsRowCount(listOf(conversation)))
    }

    @Test
    fun `blank-text messages are excluded just like the indexer skips them`() {
        // Identical input to MessageFtsTransactionTest's "2 INSERTs" case: the middle message has
        // blank text and must NOT be counted, proving expected count == indexer INSERT count.
        val nodes = listOf(
            MessageNode.of(UIMessage.user("keep me")),
            MessageNode.of(UIMessage.assistant("")),        // blank text -> skipped
            MessageNode.of(UIMessage.assistant("keep me too")),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), messages = nodes)
        assertEquals(2, expectedFtsRowCount(listOf(conversation)))
    }

    @Test
    fun `expected rows sum across multiple conversations`() {
        val c1 = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(MessageNode.of(UIMessage.user("a"))),
        )
        val c2 = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                MessageNode.of(UIMessage.user("b")),
                MessageNode.of(UIMessage.assistant("c")),
            ),
        )
        assertEquals(3, expectedFtsRowCount(listOf(c1, c2)))
    }
}
