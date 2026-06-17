package me.rerere.rikkahub.web.a2a

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertThrows
import org.junit.Test

class A2aLimitsPropertyTest {

    @Test
    fun `rpc body byte cap allows exact max and rejects max plus one`() {
        validateA2aRpcBody(MAX_A2A_RPC_BODY_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            validateA2aRpcBody(MAX_A2A_RPC_BODY_BYTES + 1)
        }
    }

    @Test
    fun `message parts cap allows exact max and rejects max plus one`() {
        val exactParts = List(MAX_A2A_MESSAGE_PARTS) { index ->
            A2aPart.TextPart("p-$index")
        }
        validateA2aMessageBounds(
            MessageSendParams(
                skillId = "skill",
                contextId = "ctx",
                message = A2aMessage(
                    messageId = "message-id",
                    role = A2aRole.USER,
                    parts = exactParts,
                ),
            )
        )

        val tooManyParts = exactParts + A2aPart.TextPart("overflow")
        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(
                MessageSendParams(
                    skillId = "skill",
                    contextId = "ctx",
                    message = A2aMessage(
                        messageId = "message-id",
                        role = A2aRole.USER,
                        parts = tooManyParts,
                    ),
                )
            )
        }
    }

    @Test
    fun `text and id bounds accept exact max and reject max plus one`() {
        val maxText = "x".repeat(MAX_A2A_TEXT_PART_CHARS)
        validateA2aMessageBounds(
            MessageSendParams(
                skillId = "skill",
                contextId = "c".repeat(MAX_A2A_ID_CHARS),
                message = A2aMessage(
                    messageId = "m".repeat(MAX_A2A_ID_CHARS),
                    role = A2aRole.USER,
                    parts = listOf(A2aPart.TextPart(maxText)),
                ),
            )
        )
        validateA2aTasksGetParams(TasksGetParams(id = "t".repeat(MAX_A2A_ID_CHARS)))
        validateA2aTasksCancelParams(TasksCancelParams(id = "t".repeat(MAX_A2A_ID_CHARS)))

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(
                MessageSendParams(
                    skillId = "skill",
                    contextId = "ctx",
                    message = A2aMessage(
                        messageId = "m".repeat(MAX_A2A_ID_CHARS),
                        role = A2aRole.USER,
                        parts = listOf(A2aPart.TextPart("${maxText}x")),
                    ),
                )
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(
                MessageSendParams(
                    skillId = "skill",
                    contextId = "ctx-${"c".repeat(MAX_A2A_ID_CHARS)}",
                    message = A2aMessage(
                        messageId = "m".repeat(MAX_A2A_ID_CHARS + 1),
                        role = A2aRole.USER,
                        parts = listOf(A2aPart.TextPart("t")),
                    ),
                )
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aTasksGetParams(TasksGetParams(id = "t".repeat(MAX_A2A_ID_CHARS + 1)))
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aTasksCancelParams(TasksCancelParams(id = "t".repeat(MAX_A2A_ID_CHARS + 1)))
        }
    }

    @Test
    fun `approval reason and answer bounds accept exact max and reject max plus one`() {
        val maxReason = "r".repeat(MAX_A2A_APPROVAL_REASON_CHARS)
        val maxAnswer = "a".repeat(MAX_A2A_APPROVAL_ANSWER_CHARS)
        validateA2aMessageBounds(
            MessageSendParams(
                skillId = "skill",
                contextId = "ctx",
                message = A2aMessage(
                    messageId = "message-id",
                    role = A2aRole.USER,
                    parts = listOf(A2aPart.TextPart("hi")),
                ),
                approval = A2aToolApproval(
                    toolCallId = "tool",
                    reason = maxReason,
                    answer = maxAnswer,
                ),
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(
                MessageSendParams(
                    skillId = "skill",
                    contextId = "ctx",
                    message = A2aMessage(
                        messageId = "message-id",
                        role = A2aRole.USER,
                        parts = listOf(A2aPart.TextPart("hi")),
                    ),
                    approval = A2aToolApproval(
                        toolCallId = "tool",
                        reason = "${maxReason}x",
                    ),
                )
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(
                MessageSendParams(
                    skillId = "skill",
                    contextId = "ctx",
                    message = A2aMessage(
                        messageId = "message-id",
                        role = A2aRole.USER,
                        parts = listOf(A2aPart.TextPart("hi")),
                    ),
                    approval = A2aToolApproval(
                        toolCallId = "tool",
                        reason = maxReason,
                        answer = "${maxAnswer}x",
                    ),
                )
            )
        }
    }

    @Test
    fun `skillId and approval toolCallId are bounded as ids`() {
        fun params(skillId: String, toolCallId: String) = MessageSendParams(
            skillId = skillId,
            contextId = "ctx",
            message = A2aMessage(
                messageId = "message-id",
                role = A2aRole.USER,
                parts = listOf(A2aPart.TextPart("hi")),
            ),
            approval = A2aToolApproval(toolCallId = toolCallId, reason = "ok"),
        )

        val maxId = "i".repeat(MAX_A2A_ID_CHARS)
        validateA2aMessageBounds(params(skillId = maxId, toolCallId = maxId))

        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(params(skillId = "${maxId}x", toolCallId = "tool"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateA2aMessageBounds(params(skillId = "skill", toolCallId = "${maxId}x"))
        }
    }
}
