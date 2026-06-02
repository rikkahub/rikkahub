package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

/**
 * Regression tests for the interrupted-generation bug: an interrupted assistant
 * turn left an empty/invalid message in the persisted sequence, so the NEXT send
 * produced ...USER, USER (after the provider dropped the empty assistant), which
 * Anthropic /v1/messages rejects with 400.
 *
 * The invariant under test: every message sequence handed to a provider request
 * builder MUST be valid — no empty messages, no two consecutive same-role
 * messages, every assistant tool_use balanced by a tool_result, and no
 * unterminated/unsigned thinking block.
 */
class ConversationSanitizeTest {

    private fun userNode(text: String) =
        UIMessage.user(text).toMessageNode()

    private fun assistantNode(text: String) =
        UIMessage.assistant(text).toMessageNode()

    private fun emptyAssistantNode() =
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()).toMessageNode()

    private fun roles(nodes: List<MessageNode>): List<MessageRole> =
        nodes.map { it.currentMessage.role }

    // case (a) FAIL-BEFORE repro: prove the invariant is actually violated on the
    // unfixed code path, using ONLY symbols shared by old and new code. The
    // provider builders forward whatever survives isValidToUpload() (Message.kt:168)
    // and do NO sequence validation, so the [user, emptyAssistant, user] shape is
    // sent verbatim minus the dropped empty assistant -> ...USER, USER. This test
    // does NOT reference sanitizeForUpload(), so it fails on master as an ASSERTION
    // (two consecutive same-role messages), not a missing-symbol compile error.
    @Test
    fun `interrupted empty assistant makes the unsanitized sent sequence invalid`() {
        val nodes = listOf(
            userNode("hi"),
            emptyAssistantNode(),
            userNode("again"),
        )

        // The exact filter the provider message-builders apply before upload.
        val sent = nodes.map { it.currentMessage }.filter { it.isValidToUpload() }

        val consecutiveSameRole = sent.zipWithNext().any { (a, b) -> a.role == b.role }
        assertTrue(
            "without sanitization the empty assistant is dropped, leaving two " +
                "consecutive USER messages that Anthropic /v1/messages rejects with 400",
            consecutiveSameRole
        )
    }

    // case (a) PRIMARY / repro: the interrupted shape — a trailing empty assistant
    // message from a cancelled stream, then a fresh user message appended.
    @Test
    fun `interrupted empty assistant between users is collapsed to a valid sequence`() {
        val nodes = listOf(
            userNode("hi"),
            emptyAssistantNode(),
            userNode("again"),
        )

        val sanitized = nodes.sanitizeForUpload()
        val sent = sanitized.map { it.currentMessage }

        // No non-uploadable (empty) message survives.
        assertTrue(
            "sanitized sequence must contain no non-uploadable message",
            sent.all { it.isValidToUpload() }
        )

        // No two consecutive same-role messages.
        sent.zipWithNext().forEach { (a, b) ->
            assertFalse(
                "sanitized sequence must not contain two consecutive same-role messages",
                a.role == b.role
            )
        }

        // Both real user turns must survive: collapsing role-adjacency must not
        // silently discard the legitimate "hi" turn in favour of "again".
        val sentText = sent.joinToString("\n") { it.toText() }
        assertTrue("the 'hi' user turn must not be lost", sentText.contains("hi"))
        assertTrue("the 'again' user turn must not be lost", sentText.contains("again"))
    }

    // case (b): an orphaned/unexecuted tool with no resumable approval must not be
    // uploaded as an unbalanced tool_use.
    @Test
    fun `orphaned unexecuted tool node is dropped`() {
        val orphanTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "",
                    toolName = "",
                    input = "",
                    output = emptyList()
                )
            )
        )
        val nodes = listOf(
            userNode("do something"),
            orphanTool.toMessageNode(),
        )

        val sanitized = nodes.sanitizeForUpload()
        val sent = sanitized.map { it.currentMessage }

        assertTrue(
            "no unexecuted, non-resumable tool may be uploaded",
            sent.flatMap { it.getTools() }.none { !it.isExecuted && !it.approvalState.canResumeToolExecution() }
        )
    }

    // case (c): an unterminated reasoning part with no signature must not be
    // uploaded as an unsigned thinking block.
    @Test
    fun `unterminated unsigned reasoning is not uploaded as a thinking block`() {
        val unterminatedReasoning = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "let me think",
                    finishedAt = null
                ),
                UIMessagePart.Text("partial answer"),
            )
        )
        val nodes = listOf(
            userNode("question"),
            unterminatedReasoning.toMessageNode(),
        )

        val sanitized = nodes.sanitizeForUpload()
        val sent = sanitized.map { it.currentMessage }

        sent.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Reasoning>()
            .forEach { reasoning ->
                assertTrue(
                    "every uploaded reasoning must be finished",
                    reasoning.finishedAt != null
                )
                assertTrue(
                    "an uploaded reasoning must carry a signature in metadata",
                    reasoning.metadata?.get("signature") != null
                )
            }
    }

    // A signed reasoning part must be preserved (after termination).
    @Test
    fun `signed reasoning is preserved`() {
        val signedReasoning = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "i reasoned",
                    finishedAt = null,
                    metadata = buildJsonObject { put("signature", JsonPrimitive("abc")) }
                ),
                UIMessagePart.Text("answer"),
            )
        )
        val nodes = listOf(
            userNode("question"),
            signedReasoning.toMessageNode(),
        )

        val sanitized = nodes.sanitizeForUpload()
        val reasoningParts = sanitized.flatMap { it.currentMessage.parts }
            .filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(1, reasoningParts.size)
        assertTrue(reasoningParts.first().finishedAt != null)
    }

    // Regression: OpenAI ChatCompletions and Google text-thoughts build finished,
    // signature-less Reasoning. A completed turn must NOT have its reasoning
    // stripped (the drop is scoped to interrupted/unterminated reasoning only).
    @Test
    fun `finished signature-less reasoning is preserved`() {
        val finishedReasoning = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "i reasoned for openai",
                    finishedAt = Clock.System.now(),
                    metadata = null
                ),
                UIMessagePart.Text("answer"),
            )
        )
        val nodes = listOf(
            userNode("question"),
            finishedReasoning.toMessageNode(),
        )

        val sanitized = nodes.sanitizeForUpload()
        val reasoningParts = sanitized.flatMap { it.currentMessage.parts }
            .filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(
            "a finished, signature-less reasoning part must be kept (no data loss)",
            1,
            reasoningParts.size
        )
        assertEquals("i reasoned for openai", reasoningParts.first().reasoning)
    }

    @Test
    fun `sanitize is idempotent`() {
        val nodes = listOf(
            userNode("hi"),
            emptyAssistantNode(),
            userNode("again"),
        )

        val once = nodes.sanitizeForUpload()
        val twice = once.sanitizeForUpload()

        assertEquals(roles(once), roles(twice))
        assertEquals(once.size, twice.size)
        assertEquals(
            once.map { it.currentMessage.toText() },
            twice.map { it.currentMessage.toText() }
        )
    }

    @Test
    fun `valid alternating sequence passes through unchanged`() {
        val nodes = listOf(
            userNode("hi"),
            assistantNode("answer"),
            userNode("more"),
        )

        val sanitized = nodes.sanitizeForUpload()

        assertEquals(roles(nodes), roles(sanitized))
        assertEquals(
            nodes.map { it.currentMessage.toText() },
            sanitized.map { it.currentMessage.toText() }
        )
    }
}
