package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.ai.agent.routing.RuleBasedIntentRouter
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `startup recovery closes pending cards and unfinished reasoning idempotently`() {
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_write_file",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
            toolExecutionId = "execution-1",
            approvalId = "approval-1",
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Reasoning("working", finishedAt = null),
                            pendingTool,
                        ),
                    )
                )
            ),
        )

        val recovered = reconcileInterruptedConversationPresentation(conversation)
        val recoveredParts = recovered.currentMessages.single().parts
        val reasoning = recoveredParts.filterIsInstance<UIMessagePart.Reasoning>().single()
        val tool = recoveredParts.filterIsInstance<UIMessagePart.Tool>().single()

        assertNotNull(reasoning.finishedAt)
        assertTrue(tool.isExecuted)
        assertTrue(tool.approvalState is ToolApprovalState.Denied)
        assertEquals(recovered, reconcileInterruptedConversationPresentation(recovered))
    }

    @Test
    fun `new send routes the raw direct-user text before user regex preprocessing`() {
        val rawParts = listOf(UIMessagePart.Text("Explain how to delete a file safely"))
        val textAfterAUserRegex = listOf(UIMessagePart.Text("Delete the file now"))

        val rawDecision = routeChatIntent(
            router = RuleBasedIntentRouter(),
            parts = rawParts,
            trust = InputTrust.USER_DIRECT,
            hasWorkspace = true,
        )
        val transformedDecision = routeChatIntent(
            router = RuleBasedIntentRouter(),
            parts = textAfterAUserRegex,
            trust = InputTrust.USER_DIRECT,
            hasWorkspace = true,
        )

        assertEquals(AgentIntent.ANSWER, rawDecision.intent)
        assertEquals(AgentIntent.EXECUTE, transformedDecision.intent)
    }

    @Test
    fun `regenerated stored user text is derived and cannot authorize execution`() {
        val decision = routeChatIntent(
            router = RuleBasedIntentRouter(),
            parts = listOf(UIMessagePart.Text("Delete the workspace file now")),
            trust = InputTrust.DERIVED_UNTRUSTED,
            hasWorkspace = true,
        )

        assertEquals(AgentIntent.EXPLORE, decision.intent)
        assertEquals("untrusted_execution_downgraded", decision.reasonCode)
    }

    @Test
    fun `execution context digest is stable content-free and run scoped`() {
        val identity = FrozenRunExecutionIdentity(
            runId = "run-a",
            conversationId = "conversation-a",
            assistantId = "assistant-a",
            modelId = "model-a",
            providerId = "provider-a",
            workspaceId = "workspace-a",
            useGlobalMemory = true,
            streamOutput = true,
            reasoningEnabled = false,
            hasConversationPrompt = true,
            hasWorkspaceCwd = true,
            modeInjectionIds = listOf("mode-b", "mode-a"),
            lorebookIds = listOf("lore-a"),
        )

        val digest = executionContextDigest(identity)

        assertEquals(digest, executionContextDigest(identity.copy()))
        assertTrue(digest.matches(Regex("sha256:[0-9a-f]{64}")))
        assertFalse(identity.toString().contains("prompt body"))
        assertFalse(identity.toString().contains("Authorization"))
        assertFalse(identity.toString().contains("tool arguments"))
        assertFalse(digest == executionContextDigest(identity.copy(runId = "run-b")))
    }

    @Test
    fun `frozen context lookup never falls back across runs`() {
        val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
        cache["run-a"] = "context-a"

        assertEquals("context-a", frozenContextOrNull(cache, "run-a"))
        assertNull(frozenContextOrNull(cache, "run-b"))
    }

    @Test
    fun `failed approval resume CAS never invokes continuation`() = runBlocking {
        var resumeCalls = 0
        var continuationCalls = 0

        val continued = continueApprovedRunIfResumed(
            hasPendingApprovals = false,
            resumeRun = {
                resumeCalls++
                false
            },
            continuation = { continuationCalls++ },
        )

        assertFalse(continued)
        assertEquals(1, resumeCalls)
        assertEquals(0, continuationCalls)
    }

    @Test
    fun `pending approval cards skip both resume CAS and continuation`() = runBlocking {
        var resumeCalls = 0
        var continuationCalls = 0

        val continued = continueApprovedRunIfResumed(
            hasPendingApprovals = true,
            resumeRun = {
                resumeCalls++
                true
            },
            continuation = { continuationCalls++ },
        )

        assertFalse(continued)
        assertEquals(0, resumeCalls)
        assertEquals(0, continuationCalls)
    }

    @Test
    fun `answer false appends the message without preparing an agent run`() {
        assertFalse(shouldPrepareAgentRun(answer = false))
        assertTrue(shouldPrepareAgentRun(answer = true))
    }

    @Test
    fun `run side effects require both current lease and its bound run`() {
        assertFalse(canPublishRunSideEffect(isCurrentLease = false, boundRunId = "run-a", runId = "run-a"))
        assertFalse(canPublishRunSideEffect(isCurrentLease = true, boundRunId = "run-b", runId = "run-a"))
        assertTrue(canPublishRunSideEffect(isCurrentLease = true, boundRunId = "run-a", runId = "run-a"))
    }

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
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
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
