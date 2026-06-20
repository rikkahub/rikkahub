package me.rerere.rikkahub.web.a2a

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

private class ApprovalResumeFakeClient(private val resumeJob: Job?) : A2aMessageFlowClient {
    override suspend fun sendMessageReturningJob(conversationId: Uuid, content: List<UIMessagePart>): Job =
        error("sendMessageReturningJob should not run on the approval path")

    override suspend fun handleToolApprovalReturningJob(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): Job? = resumeJob

    override suspend fun initializeConversationForSkill(contextId: Uuid, assistantId: Uuid) =
        error("initializeConversationForSkill should not run on the approval path")
}

/**
 * Compatibility contract with spec A2A clients (the Hermes plugin et al.):
 * a missing skillId defaults to the card's primary skill, contextId may live in
 * the message or be omitted entirely, and `message/send` rests on terminal or
 * input-required states.
 */
class A2aHermesCompatTest {

    private fun settingsWith(assistants: List<Assistant>, currentId: Uuid): Settings =
        Settings(assistants = assistants, assistantId = currentId)

    private fun paramsWith(topContextId: String?, messageContextId: String?): MessageSendParams =
        MessageSendParams(
            contextId = topContextId,
            skillId = null,
            message = A2aMessage(
                messageId = "m-1",
                role = A2aRole.USER,
                parts = listOf(A2aPart.TextPart("hi")),
                contextId = messageContextId,
            ),
        )

    // ── skillId default (FIX C) ────────────────────────────────────────────

    @Test
    fun `missing skillId defaults to the current assistant when spawnable`() {
        val current = Assistant(id = Uuid.random(), spawnable = true)
        val other = Assistant(id = Uuid.random(), spawnable = true)
        val settings = settingsWith(listOf(current, other), current.id)

        assertEquals(current.id, resolveSpawnableSkill(settings, null).id)
    }

    @Test
    fun `missing skillId falls back to the first spawnable when current is not spawnable`() {
        val current = Assistant(id = Uuid.random(), spawnable = false)
        val spawnable = Assistant(id = Uuid.random(), spawnable = true)
        val settings = settingsWith(listOf(current, spawnable), current.id)

        assertEquals(spawnable.id, resolveSpawnableSkill(settings, null).id)
    }

    @Test
    fun `missing skillId with no spawnable assistant is rejected`() {
        val only = Assistant(id = Uuid.random(), spawnable = false)
        val settings = settingsWith(listOf(only), only.id)

        val error = runCatching { resolveSpawnableSkill(settings, null) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `explicit skillId still resolves the addressed assistant`() {
        val target = Assistant(id = Uuid.random(), spawnable = true)
        val settings = settingsWith(listOf(target), target.id)

        assertEquals(target.id, resolveSpawnableSkill(settings, target.id.toString()).id)
    }

    // ── contextId resolution (FIX D) ───────────────────────────────────────

    @Test
    fun `top-level contextId wins over the in-message one`() {
        val top = Uuid.random()
        val inMessage = Uuid.random()
        assertEquals(top, resolveA2aContextId(paramsWith(top.toString(), inMessage.toString())))
    }

    @Test
    fun `contextId falls back to the in-message field`() {
        val inMessage = Uuid.random()
        assertEquals(inMessage, resolveA2aContextId(paramsWith(null, inMessage.toString())))
    }

    @Test
    fun `absent contextId mints a fresh conversation`() {
        val a = resolveA2aContextId(paramsWith(null, null))
        val b = resolveA2aContextId(paramsWith(null, null))
        assertNotNull(a)
        assertNotEquals(a, b)
    }

    @Test
    fun `malformed contextId is surfaced, not silently replaced`() {
        val error = runCatching { resolveA2aContextId(paramsWith("not-a-uuid", null)) }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    // ── synchronous message/send resting states (FIX E) ────────────────────

    @Test
    fun `resting predicate covers terminal states and input-required`() {
        assertTrue(A2aTaskState.COMPLETED.isA2aSyncResting())
        assertTrue(A2aTaskState.FAILED.isA2aSyncResting())
        assertTrue(A2aTaskState.CANCELED.isA2aSyncResting())
        assertTrue(A2aTaskState.INPUT_REQUIRED.isA2aSyncResting())
        assertFalse(A2aTaskState.SUBMITTED.isA2aSyncResting())
        assertFalse(A2aTaskState.WORKING.isA2aSyncResting())
    }

    @Test
    fun `a sync waiter unblocks when the task reaches input-required`() = runBlocking {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "message-1") as A2aAdmission.Accepted).entry

        val resting = async(start = CoroutineStart.UNDISPATCHED) {
            entry.status.first { it.state.isA2aSyncResting() }.state
        }

        registry.transition(entry.taskId, A2aTaskState.INPUT_REQUIRED)

        withTimeout(1_000) {
            assertEquals(A2aTaskState.INPUT_REQUIRED, resting.await())
        }
    }

    @Test
    fun `approval resume moves the task off input-required so a sync waiter keeps blocking`() = runBlocking {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val assistantId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val entry = (registry.admit(contextId, assistantId, "m-init") as A2aAdmission.Accepted).entry
        registry.transition(entry.taskId, A2aTaskState.INPUT_REQUIRED)
        // Precondition: a sync waiter on the resting input-required state would
        // short-circuit if the approval path left it untouched.
        assertTrue(entry.state.isA2aSyncResting())

        val conversation = conversationWithPendingApproval(contextId, assistantId, "tool-1")

        val result = startOrResumeA2aTask(
            appScope = this,
            params = MessageSendParams(
                taskId = entry.taskId,
                skillId = null,
                message = A2aMessage(
                    messageId = "m-approve",
                    role = A2aRole.USER,
                    parts = listOf(A2aPart.TextPart("ok")),
                ),
                approval = A2aToolApproval(toolCallId = "tool-1", approved = true),
            ),
            messageFlowClient = ApprovalResumeFakeClient(resumeJob = Job()),
            registry = registry,
            getConversation = { conversation },
            resolveSpawnableSkill = { error("skill resolution should not run on the approval path") },
        )

        assertEquals(entry.taskId, result.taskId)
        assertEquals(A2aTaskState.WORKING, result.state)
        assertFalse(result.state.isA2aSyncResting())
    }

    private fun conversationWithPendingApproval(
        contextId: Uuid,
        assistantId: Uuid,
        toolCallId: String,
    ): Conversation = Conversation(
        id = contextId,
        assistantId = assistantId,
        messageNodes = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = toolCallId,
                        toolName = "ask_user",
                        input = "continue?",
                        approvalState = ToolApprovalState.Pending,
                    ),
                ),
            ).toMessageNode(),
        ),
    )
}
