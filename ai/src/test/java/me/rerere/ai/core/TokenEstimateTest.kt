package me.rerere.ai.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10 / M2 (estimator) and M1 (canonical measurement) for design #193 Stage 1.
 *
 * estimateTokens is the conservative no-tokenizer heuristic that covers the small tail not yet
 * reflected in a real server-seen usage reading. The properties that the trigger relies on:
 * - non-negativity, non-empty -> positive (so a zero estimate can't make the trigger under-fire);
 * - monotonicity under content append (more content never lowers the estimate);
 * - binary/media parts get a FLAT estimate, never char-counted base64 (a 1 MB image must not read
 *   as ~325k tokens and spuriously fire destructive auto-compact).
 *
 * contextTokens (M1) is deterministic, depends only on its input, and anchors on the LAST
 * usage-bearing message's real totalTokens plus an estimate of the tail appended after it.
 */
class TokenEstimateTest {

    private fun text(s: String) = UIMessagePart.Text(s)
    private fun userText(s: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(text(s)))

    // ---- P10 / M2: estimator conservativeness + monotonicity ----

    @Test
    fun `P10 estimate is non-negative and non-empty text is positive`() {
        assertEquals(0, estimateTokens(text("")))
        assertTrue(estimateTokens(text("a")) > 0)
        assertTrue(estimateTokens(text("hello world this is a sentence")) > 0)
    }

    @Test
    fun `M2 appending content never decreases the estimate`() {
        runBlocking {
            checkAll(Arb.string(0..50), Arb.string(0..50)) { a, b ->
                val justA = estimateTokens(text(a))
                val aPlusB = estimateTokens(text(a + b))
                assertTrue("estimate must be monotone under append", aPlusB >= justA)
            }
        }
    }

    @Test
    fun `M2 estimate over a part list is monotone when a part is appended`() {
        runBlocking {
            checkAll(Arb.list(Arb.string(0..30), 0..6), Arb.string(0..30)) { strings, extra ->
                val base = strings.map { text(it) }
                val grown = base + text(extra)
                assertTrue(estimateTokens(grown) >= estimateTokens(base))
            }
        }
    }

    @Test
    fun `media parts use a flat estimate, not base64 char count`() {
        // A large base64 data URL must NOT inflate the estimate proportionally to its length.
        val hugeBase64 = "data:image/png;base64," + "A".repeat(1_000_000)
        val img = UIMessagePart.Image(url = hugeBase64)
        val estimate = estimateTokens(img)
        assertEquals(MEDIA_PART_TOKEN_ESTIMATE, estimate)
        // Sanity: char-counting would have produced an enormous number; the flat estimate is tiny
        // relative to a 1 MB string.
        assertTrue(estimate < 1_000_000 / 4)
    }

    @Test
    fun `each media modality is the same flat estimate`() {
        assertEquals(MEDIA_PART_TOKEN_ESTIMATE, estimateTokens(UIMessagePart.Image("file://x")))
        assertEquals(MEDIA_PART_TOKEN_ESTIMATE, estimateTokens(UIMessagePart.Video("file://x")))
        assertEquals(MEDIA_PART_TOKEN_ESTIMATE, estimateTokens(UIMessagePart.Audio("file://x")))
        assertEquals(
            MEDIA_PART_TOKEN_ESTIMATE,
            estimateTokens(UIMessagePart.Document(url = "file://x", fileName = "x.pdf"))
        )
    }

    @Test
    fun `tool input contributes to the estimate and output is counted recursively`() {
        val noOutput = UIMessagePart.Tool(
            toolCallId = "1",
            toolName = "search",
            input = """{"q":"a long query string here"}""",
            output = emptyList(),
        )
        val withOutput = noOutput.copy(
            output = listOf(text("a".repeat(400)))
        )
        assertTrue(estimateTokens(noOutput) > 0)
        assertTrue("tool output must add to the estimate", estimateTokens(withOutput) > estimateTokens(noOutput))
    }

    // ---- M1: contextTokens determinism + anchoring ----

    @Test
    fun `M1 contextTokens is deterministic and order-independent of call`() {
        val messages = listOf(
            userText("first"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(text("reply")),
                usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120),
            ),
            userText("a pending question"),
        )
        val a = contextTokens(messages)
        val b = contextTokens(messages)
        assertEquals(a, b)
    }

    @Test
    fun `M1 anchors on real total tokens plus estimate of the tail after the anchor`() {
        val pending = "a".repeat(40)
        val anchorTotal = 120
        val messages = listOf(
            userText("old"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(text("reply")),
                usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = anchorTotal),
            ),
            userText(pending),
        )
        val expectedTail = estimateTokens(listOf(text(pending)))
        assertEquals(anchorTotal + expectedTail, contextTokens(messages))
    }

    @Test
    fun `M1 cold start with no usage estimates the whole list`() {
        val messages = listOf(userText("hello"), userText("world"))
        assertEquals(estimateTokensForMessages(messages), contextTokens(messages))
    }

    @Test
    fun `M1 uses the LAST usage-bearing message as anchor`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(text("a")),
                usage = TokenUsage(totalTokens = 50),
            ),
            userText("mid"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(text("b")),
                usage = TokenUsage(totalTokens = 999),
            ),
        )
        // Anchor is the last usage (999); nothing after it -> exactly 999.
        assertEquals(999, contextTokens(messages))
    }

    @Test
    fun `M1 empty list is zero`() {
        assertEquals(0, contextTokens(emptyList()))
    }

    @Test
    fun `M1 a non-null zero usage does not shadow an earlier real total`() {
        // Regression (design #193, finding #1): ChatCompletionsAPI.parseTokenUsage returns null ONLY
        // when the whole `usage` object is absent; a chunk carrying `"usage": {}` (or a cancelled /
        // interrupted stream) yields a non-null TokenUsage(0,0,0,0), which TokenUsage?.merge preserves
        // as a non-null zero on a fresh assistant turn. Anchoring on `usage != null` would pick that
        // later zero (anchorTotal = 0) and SHADOW the earlier turn's real 50k total, collapsing
        // contextTokens to ~0 and silently disabling the trigger/warning. The anchor must key on a real
        // reading (totalTokens > 0), so the real 50k stays the footprint.
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(text("first reply")),
                usage = TokenUsage(promptTokens = 49_000, completionTokens = 1_000, totalTokens = 50_000),
            ),
            userText("next question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(text("interrupted reply")),
                usage = TokenUsage(0, 0, 0, 0), // non-null but all-zero (e.g. "usage": {})
            ),
        )
        assertTrue(
            "a zero-usage later turn must not collapse the footprint below the real 50k total",
            contextTokens(messages) >= 50_000,
        )
    }

    @Test
    fun `M1 clearing stale usage drops the anchor to a conservative estimate`() {
        // Models the post-compaction invariant (design #193): a kept assistant message whose usage
        // recorded a now-summarized prefix is a stale-high anchor. With usage present, contextTokens
        // returns that huge total; clearing usage makes it estimate the (small) remaining content
        // instead -- which is why compressConversation strips usage from kept messages so the trigger
        // and warning don't immediately re-fire on a conversation we just shrank.
        val staleAnchor = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(text("short kept reply")),
            usage = TokenUsage(promptTokens = 500_000, completionTokens = 1_000, totalTokens = 501_000),
        )
        val withStale = listOf(staleAnchor)
        val cleared = listOf(staleAnchor.copy(usage = null))

        assertEquals(501_000, contextTokens(withStale))
        val estimated = contextTokens(cleared)
        assertTrue("cleared anchor must estimate, not report the stale total", estimated < 1_000)
        assertEquals(estimateTokensForMessages(cleared), estimated)
    }
}
