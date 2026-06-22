package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_MODEL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentCompletion
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.syntheticAgentEventMarker
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The background-subagent analogue of [DeferredShellCompletionPropertyTest]. Unlike a deferred shell
 * tool, the background `agent` running marker is an EXECUTED tool output (not [UIMessagePart.Tool.asDeferred]),
 * so discovery and resolution key off the `{status:"running"}` payload — never `isDeferred`.
 */
class BackgroundSubagentCompletionTest {

    private fun runningMarker(taskId: Uuid, subagent: String = "Researcher"): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "call-agent",
            toolName = SPAWN_TOOL_MODEL_NAME,
            input = """{"subagent":"$subagent","prompt":"go","background":true}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"running","taskId":"$taskId","background":true,"subagent":"$subagent"}"""
                )
            ),
        )

    private fun succeededPayload(taskId: Uuid): String =
        """{"taskId":"$taskId","status":"SUCCEEDED","summary":"found it","steps":3,"tokens":1200}"""

    @Test
    fun `background running markers are discovered from executed tool parts, not deferred`() {
        val taskId = Uuid.random()
        val tool = runningMarker(taskId)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val node = MessageNode(messages = listOf(message))
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(node),
        )

        assertFalse("the background marker is an executed tool, never deferred", tool.isDeferred)
        val anchors = findBackgroundSubagentToolAnchors(conversation)

        assertEquals(1, anchors.size)
        val (foundTaskId, anchor) = anchors.single()
        assertEquals(taskId, foundTaskId)
        assertEquals(tool.toolCallId, anchor.toolCallId)
        assertEquals(node.id, anchor.toolNodeId)
        assertEquals(message.id, anchor.toolMessageId)
    }

    @Test
    fun `the selected-branch guard is true when the spawn anchor is on the selected branch`() {
        val taskId = Uuid.random()
        val tool = runningMarker(taskId)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val node = MessageNode(messages = listOf(message))
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(node),
        )
        val anchor = findBackgroundSubagentToolAnchors(conversation).single().second

        assertTrue(
            "a completion on the selected branch continues generation",
            isBackgroundAnchorOnSelectedBranch(conversation, anchor),
        )
    }

    @Test
    fun `the selected-branch guard is false when the user regenerated away from the spawn branch`() {
        val taskId = Uuid.random()
        val marker = runningMarker(taskId)
        val abandonedBranch = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(marker))
        val selectedBranch = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("regenerated")))
        // selectIndex = 1 => the user switched to the second branch; the spawn lives in the abandoned first.
        val node = MessageNode(messages = listOf(abandonedBranch, selectedBranch), selectIndex = 1)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(node),
        )
        val anchor = me.rerere.rikkahub.data.ai.task.SubagentToolAnchor(
            toolCallId = marker.toolCallId,
            toolNodeId = node.id,
            toolMessageId = abandonedBranch.id,
        )

        assertFalse(
            "a completion from a non-selected branch must not continue generation",
            isBackgroundAnchorOnSelectedBranch(conversation, anchor),
        )
    }

    @Test
    fun `the selected-branch guard is false when the spawn node was truncated away`() {
        // A regenerate can truncate away the node that held the spawn marker while the detached run keeps
        // going; its persisted anchor then points at a node that no longer exists. That is the
        // regenerated-away case — it must NOT auto-continue (a known anchor with a missing node != anchorless).
        val survivingNode = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("kept")))),
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(survivingNode),
        )
        val anchor = me.rerere.rikkahub.data.ai.task.SubagentToolAnchor(
            toolCallId = "call-agent",
            toolNodeId = Uuid.random(), // a node id no longer present in the conversation
            toolMessageId = Uuid.random(),
        )

        assertFalse(
            "a completion whose spawn node was truncated must not continue generation",
            isBackgroundAnchorOnSelectedBranch(conversation, anchor),
        )
    }

    @Test
    fun `a running marker without a taskId yields no anchor and falls back to the synthetic message`() {
        val payload = succeededPayload(Uuid.random())
        val taskIdLessMarker = UIMessagePart.Tool(
            toolCallId = "call-agent",
            toolName = SPAWN_TOOL_MODEL_NAME,
            input = """{"subagent":"X","prompt":"go","background":true}""",
            output = listOf(UIMessagePart.Text("""{"status":"running","background":true}""")),
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(taskIdLessMarker)).toMessageNode()
            ),
        )

        assertTrue(findBackgroundSubagentToolAnchors(conversation).isEmpty())
        val synthetic = buildSyntheticAgentEventMessage(
            me.rerere.rikkahub.data.db.entity.AgentEventEntity(
                id = "event-1",
                conversationId = conversation.id.toString(),
                dedupeKey = "subagent:abc",
                enqueueSeq = 1L,
                kind = SubagentCompletion.KIND,
                payloadJson = payload,
                status = me.rerere.rikkahub.data.db.entity.AgentEventStatus.PENDING.name,
                createdAt = 1L,
            )
        )
        assertEquals(MessageRole.ASSISTANT, synthetic.role)
        val syntheticTool = synthetic.parts.single() as UIMessagePart.Tool
        assertEquals(sanitizeSyntheticToolName(SubagentCompletion.KIND), syntheticTool.toolName)
        assertEquals(payload, (syntheticTool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a completed marker is no longer discoverable as running`() {
        val taskId = Uuid.random()
        val resolved = runningMarker(taskId).copy(
            output = listOf(UIMessagePart.Text(succeededPayload(taskId))),
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(resolved)).toMessageNode()
            ),
        )
        assertNull(runningSubagentTaskId(resolved))
        assertTrue(findBackgroundSubagentToolAnchors(conversation).isEmpty())
    }

    private fun completionEvent(payload: String, id: String = "evt-1"): AgentEventEntity =
        AgentEventEntity(
            id = id,
            conversationId = Uuid.random().toString(),
            dedupeKey = id,
            enqueueSeq = 1L,
            kind = SubagentCompletion.KIND,
            payloadJson = payload,
            status = AgentEventStatus.PENDING.name,
            createdAt = 1L,
        )

    @Test
    fun `the completion is delivered as a USER notice carrying the synthetic marker and outcome`() {
        // The load-bearing fix: a background completion arrives AFTER the parent turn already ended on an
        // assistant message, so it MUST be a USER turn — else the continuation has no user message to
        // respond to (provider rejects assistant prefill) and the parent is never told the outcome.
        val taskId = Uuid.random()
        val notice = buildSubagentCompletionNotice(
            completionEvent("""{"taskId":"$taskId","status":"SUCCEEDED","summary":"found 3 results","steps":4,"tokens":900}""")
        )

        assertEquals(MessageRole.USER, notice.role)
        assertEquals(SubagentCompletion.KIND to "evt-1", notice.syntheticAgentEventMarker())
        val text = (notice.parts.single() as UIMessagePart.Text).text
        assertTrue("notice states completion", text.contains("completed"))
        assertTrue("notice carries the subagent result", text.contains("found 3 results"))
        assertTrue("notice references the task id so the parent need not poll", text.contains(taskId.toString()))
    }

    @Test
    fun `a failed completion notice surfaces the error and never fakes success`() {
        val text = (buildSubagentCompletionNotice(
            completionEvent("""{"taskId":"${Uuid.random()}","status":"FAILED","error":"boom","steps":1,"tokens":10}""")
        ).parts.single() as UIMessagePart.Text).text

        assertTrue("failed status is surfaced", text.contains("FAILED"))
        assertTrue("the error is surfaced", text.contains("boom"))
        assertFalse("a failure is never reported as completed", text.contains("completed"))
    }

    @Test
    fun `the notice renderer is robust to a garbled payload`() {
        val text = renderSubagentCompletionText("not json")
        assertTrue(text.contains("Background subagent"))
    }
}
