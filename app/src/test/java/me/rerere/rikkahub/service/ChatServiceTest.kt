package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `multiple legacy tool matches are rejected instead of bulk approval`() {
        val requested = UIMessagePart.Tool(
            toolCallId = "same-call",
            toolName = "workspace_write_file",
            input = "{\"path\":\"a.txt\"}",
            approvalState = ToolApprovalState.Pending,
        )

        assertEquals(
            null,
            selectLegacyApprovalCard(requested, listOf(requested, requested.copy()), pendingApprovalCount = 1),
        )
    }

    @Test
    fun `multiple pending legacy approvals are rejected even for one matching card`() {
        val requested = UIMessagePart.Tool(
            toolCallId = "same-call",
            toolName = "workspace_write_file",
            input = "{\"path\":\"a.txt\"}",
            approvalState = ToolApprovalState.Pending,
        )

        assertEquals(
            null,
            selectLegacyApprovalCard(requested, listOf(requested), pendingApprovalCount = 2),
        )
    }

    @Test
    fun `expired approval replacement rebinds only its original pending tool card`() {
        val expiringCard = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_write_file",
            input = "{\"path\":\"a.txt\"}",
            approvalState = ToolApprovalState.Pending,
            toolExecutionId = "execution-1",
            approvalId = "expired-approval",
        )
        val otherCard = expiringCard.copy(approvalId = "other-approval")
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(expiringCard, otherCard),
                        ),
                    ),
                ),
            ),
        )

        val updated = rebindExpiredApprovalCard(
            conversation,
            executionId = "execution-1",
            expiredApprovalId = "expired-approval",
            replacementApprovalId = "replacement-approval",
        )

        assertNotNull(updated)
        val cards = updated!!.currentMessages.single().getTools()
        assertEquals("replacement-approval", cards[0].approvalId)
        assertEquals("授权已过期，请重新确认", cards[0].approvalStatusMessage)
        assertEquals(ToolApprovalState.Pending, cards[0].approvalState)
        assertEquals("other-approval", cards[1].approvalId)
    }
}
