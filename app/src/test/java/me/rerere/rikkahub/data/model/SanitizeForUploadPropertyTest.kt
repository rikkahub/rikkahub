package me.rerere.rikkahub.data.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property suite for [sanitizeForUpload].
 *
 * The fix makes the sanitizer CONTENT-PRESERVING for text/reasoning while still
 * enforcing the upload invariant (no unbalanced orphan tool_use, no
 * unterminated+unsigned reasoning). These properties pin both halves.
 *
 * #1 TEXT PRESERVATION — every non-blank Text and every finished Reasoning in a
 *    selected message survives sanitization (the bug dropped whole branches,
 *    discarding this content). FAILS on master for the orphan-tool case.
 * #2 BALANCE — no retained tool is an unbalanced orphan (unexecuted AND not
 *    resumable) except a legitimately Pending tool awaiting approval.
 * #3 IDEMPOTENCE — sanitize is a fixed point: sanitize(sanitize(x)) == sanitize(x)
 *    structurally (same roles + same selected-message parts).
 * #4 METAMORPHIC — an empty-output Auto tool and the same tool given a synthetic
 *    non-empty output yield equivalent retention and balance.
 */
class SanitizeForUploadPropertyTest {

    private val arbApproval: Arb<ToolApprovalState> = Arb.element(
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
        ToolApprovalState.Approved,
        ToolApprovalState.Denied("no"),
        ToolApprovalState.Answered("yes"),
    )

    private val arbPart: Arb<UIMessagePart> = arbitrary {
        when (Arb.int(0..4).bind()) {
            0 -> UIMessagePart.Text("t" + Arb.string(0..6).bind())
            1 -> UIMessagePart.Reasoning(
                reasoning = "r" + Arb.string(0..6).bind(),
                finishedAt = kotlin.time.Clock.System.now(),
                metadata = buildJsonObject { put("signature", JsonPrimitive("sig")) }
            )
            2 -> UIMessagePart.Reasoning(
                // unterminated + unsigned: intentionally dropped by the sanitizer.
                reasoning = "r" + Arb.string(0..6).bind(),
                finishedAt = null,
                metadata = null
            )
            3 -> UIMessagePart.Tool(
                toolCallId = "id" + Arb.string(0..4).bind(),
                toolName = "tool",
                input = "{}",
                output = if (Arb.boolean().bind()) {
                    listOf(UIMessagePart.Text("done"))
                } else {
                    emptyList()
                },
                approvalState = arbApproval.bind(),
            )
            else -> UIMessagePart.Tool(
                toolCallId = "id" + Arb.string(0..4).bind(),
                toolName = "tool",
                input = "{}",
                output = emptyList(),
                approvalState = arbApproval.bind(),
            )
        }
    }

    private val arbMessage: Arb<UIMessage> = arbitrary {
        UIMessage(
            role = Arb.element(MessageRole.USER, MessageRole.ASSISTANT).bind(),
            parts = Arb.list(arbPart, 1..3).bind(),
        )
    }

    private val arbNode: Arb<MessageNode> = arbitrary {
        val messages = Arb.list(arbMessage, 1..4).bind()
        MessageNode(
            messages = messages,
            selectIndex = Arb.int(0 until messages.size).bind(),
        )
    }

    private val arbNodes: Arb<List<MessageNode>> = Arb.list(arbNode, 0..5)

    private fun finishedReasoningTexts(message: UIMessage): Set<String> =
        message.parts.filterIsInstance<UIMessagePart.Reasoning>()
            .filter { it.finishedAt != null && it.reasoning.isNotBlank() }
            .map { it.reasoning }
            .toSet()

    private fun nonBlankTexts(message: UIMessage): Set<String> =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .filter { it.text.isNotBlank() }
            .map { it.text }
            .toSet()

    private fun allSurvivingTexts(nodes: List<MessageNode>): Set<String> =
        nodes.flatMap { it.currentMessage.parts.filterIsInstance<UIMessagePart.Text>() }
            .map { it.text }
            .toSet()

    private fun allSurvivingReasoning(nodes: List<MessageNode>): Set<String> =
        nodes.flatMap { it.currentMessage.parts.filterIsInstance<UIMessagePart.Reasoning>() }
            .map { it.reasoning }
            .toSet()

    @Test
    fun `property 1 - non-blank text and finished reasoning are preserved`() {
        runBlocking {
            checkAll(200, arbNodes) { nodes ->
                val sanitized = nodes.sanitizeForUpload()
                val survivingText = allSurvivingTexts(sanitized)
                val survivingReasoning = allSurvivingReasoning(sanitized)

                // For every node, the selected message's non-blank text and finished
                // reasoning must still appear somewhere in the sanitized output.
                // (collapseConsecutiveSameRole may MERGE same-role nodes, so check
                // membership across the whole sanitized list, not per-node.)
                nodes.forEach { node ->
                    val message = node.currentMessage
                    nonBlankTexts(message).forEach { text ->
                        assertTrue(
                            "non-blank text '$text' must survive sanitization",
                            survivingText.contains(text)
                        )
                    }
                    finishedReasoningTexts(message).forEach { reasoning ->
                        assertTrue(
                            "finished reasoning '$reasoning' must survive sanitization",
                            survivingReasoning.contains(reasoning)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `property 2 - no retained tool is an unbalanced orphan except a pending one`() {
        runBlocking {
            checkAll(200, arbNodes) { nodes ->
                val sanitized = nodes.sanitizeForUpload()
                sanitized.flatMap { it.currentMessage.getTools() }.forEach { tool ->
                    if (!tool.isExecuted && !tool.approvalState.canResumeToolExecution()) {
                        assertTrue(
                            "an unexecuted non-resumable tool may only survive if Pending",
                            tool.approvalState is ToolApprovalState.Pending
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `property 3 - sanitize is idempotent`() {
        runBlocking {
            checkAll(200, arbNodes) { nodes ->
                val once = nodes.sanitizeForUpload()
                val twice = once.sanitizeForUpload()

                assertEquals(
                    once.map { it.currentMessage.role },
                    twice.map { it.currentMessage.role }
                )
                assertEquals(
                    once.map { it.currentMessage.parts },
                    twice.map { it.currentMessage.parts }
                )
            }
        }
    }

    @Test
    fun `property 4 - empty-output Auto tool matches synthetic non-empty output for retention and balance`() {
        runBlocking {
            // The metamorphic subject is the ASSISTANT tool message (empty vs
            // synthetic non-empty output). The user prefix is a fixed, clean,
            // uploadable USER turn so the comparison isolates the tool transform.
            checkAll(200, Arb.string(1..8)) { userText ->
                val userMessage = UIMessage.user("u$userText")
                val emptyToolMessage = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("answer"),
                        UIMessagePart.Tool(
                            toolCallId = "call_1",
                            toolName = "tool",
                            input = "{}",
                            output = emptyList(),
                            approvalState = ToolApprovalState.Auto,
                        ),
                    )
                )
                val filledToolMessage = emptyToolMessage.copy(
                    parts = listOf(
                        UIMessagePart.Text("answer"),
                        UIMessagePart.Tool(
                            toolCallId = "call_1",
                            toolName = "tool",
                            input = "{}",
                            output = listOf(UIMessagePart.Text("synthetic")),
                            approvalState = ToolApprovalState.Auto,
                        ),
                    )
                )

                val emptyNodes = listOf(userMessage.toMessageNode(), emptyToolMessage.toMessageNode())
                val filledNodes = listOf(userMessage.toMessageNode(), filledToolMessage.toMessageNode())

                val emptySan = emptyNodes.sanitizeForUpload()
                val filledSan = filledNodes.sanitizeForUpload()

                // Equivalent retention: both keep the assistant node.
                assertEquals(emptySan.size, filledSan.size)
                // Equivalent balance: neither leaves an unbalanced orphan tool.
                listOf(emptySan, filledSan).forEach { san ->
                    assertTrue(
                        san.flatMap { it.currentMessage.getTools() }
                            .none { !it.isExecuted && !it.approvalState.canResumeToolExecution() }
                    )
                }
                // Both preserve the assistant text.
                listOf(emptySan, filledSan).forEach { san ->
                    assertTrue(
                        san.flatMap { it.currentMessage.parts.filterIsInstance<UIMessagePart.Text>() }
                            .any { it.text == "answer" }
                    )
                }
            }
        }
    }
}
