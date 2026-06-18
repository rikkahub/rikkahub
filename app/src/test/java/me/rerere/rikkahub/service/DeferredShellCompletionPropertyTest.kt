package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.shellrun.ShellCompletion
import me.rerere.rikkahub.data.ai.shellrun.ShellRunToolAnchor
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DeferredShellCompletionPropertyTest {
    @Test
    fun `deferred shell anchors are discovered from visible tool parts`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val taskId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "call-shell",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1","detachAfterSeconds":1}""",
            output = listOf(UIMessagePart.Text("""{"taskId":"$taskId","status":"running"}""")),
        ).asDeferred()
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val node = MessageNode(messages = listOf(message))
        val conversation = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = listOf(node),
        )

        val anchors = findDeferredShellToolAnchors(conversation)

        assertEquals(1, anchors.size)
        assertEquals(taskId, anchors.single().taskId)
        assertEquals(tool.toolCallId, anchors.single().anchor.toolCallId)
        assertEquals(node.id, anchors.single().anchor.toolNodeId)
        assertEquals(message.id, anchors.single().anchor.toolMessageId)
    }

    @Test
    fun `completion resolves into original tool output and never creates a user message`() {
        val taskId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "call-shell",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1","detachAfterSeconds":1}""",
            output = listOf(UIMessagePart.Text("""{"taskId":"$taskId","status":"running"}""")),
        ).asDeferred()
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val node = MessageNode(messages = listOf(message))
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(node),
        )
        val anchor = findDeferredShellToolAnchors(conversation).firstOrNull { it.taskId == taskId }?.anchor
        val payload = """{"taskId":"$taskId","status":"SUCCEEDED","exitCode":0,"tail":"done"}"""

        assertNotNull("the drain must derive an anchor from the deferred tool when the store has none", anchor)
        val first = resolveDeferredShellCompletion(conversation, anchor!!, payload)
        assertNotNull(first)
        assertTrue(first!!.continueGeneration)
        assertEquals(1, first.conversation.messageNodes.size)
        assertFalse(first.conversation.currentMessages.any { it.role == MessageRole.USER })
        val resolvedTool = first.conversation.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertFalse(resolvedTool.isDeferred)
        assertEquals(payload, (resolvedTool.output.single() as UIMessagePart.Text).text)

        val duplicate = resolveDeferredShellCompletion(first.conversation, anchor, payload)
        assertNotNull(duplicate)
        assertFalse(duplicate!!.continueGeneration)
        val finalTools = duplicate.conversation.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, finalTools.count { (it.output.singleOrNull() as? UIMessagePart.Text)?.text == payload })
    }

    @Test
    fun `completion on a non-selected branch resolves output but does not continue generation`() {
        val taskId = Uuid.random()
        val deferredTool = UIMessagePart.Tool(
            toolCallId = "call-shell",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1","detachAfterSeconds":1}""",
            output = listOf(UIMessagePart.Text("""{"taskId":"$taskId","status":"running"}""")),
        ).asDeferred()
        val abandonedBranch = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(deferredTool))
        val selectedBranch = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("regenerated")))
        // selectIndex = 1 -> the user switched to the second branch; the deferred shell lives in the
        // first (abandoned) branch and must NOT drive a continuation on the selected branch.
        val node = MessageNode(messages = listOf(abandonedBranch, selectedBranch), selectIndex = 1)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(node),
        )
        // The persisted anchor was attached while the deferred tool's branch was still selected; the
        // user has since regenerated and switched to the other branch. (On-the-fly discovery only
        // scans the selected branch, so this scenario is reached through the persisted anchor.)
        val anchor = ShellRunToolAnchor(
            toolCallId = deferredTool.toolCallId,
            toolNodeId = node.id,
            toolMessageId = abandonedBranch.id,
        )
        val payload = """{"taskId":"$taskId","status":"SUCCEEDED","exitCode":0,"tail":"done"}"""

        val resolution = resolveDeferredShellCompletion(conversation, anchor, payload)
        assertNotNull(resolution)
        assertFalse("a completion from a non-selected branch must not continue generation", resolution!!.continueGeneration)
        // The abandoned branch's tool output is still resolved for history correctness.
        val resolvedTool = resolution.conversation.messageNodes.single()
            .messages.first().parts.single() as UIMessagePart.Tool
        assertFalse(resolvedTool.isDeferred)
        assertEquals(payload, (resolvedTool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `completion with no matching deferred tool falls back to synthetic assistant tool`() {
        val taskId = Uuid.random()
        val payload = """{"taskId":"$taskId","status":"SUCCEEDED","exitCode":0,"tail":"done"}"""
        val taskIdLessRunningMarker = UIMessagePart.Tool(
            toolCallId = "call-shell",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1","detachAfterSeconds":1}""",
            output = listOf(UIMessagePart.Text("""{"status":"running"}""")),
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(taskIdLessRunningMarker)).toMessageNode()
            ),
        )
        val event = AgentEventEntity(
            id = "event-1",
            conversationId = conversation.id.toString(),
            dedupeKey = "shell:$taskId",
            enqueueSeq = 1L,
            kind = ShellCompletion.KIND,
            payloadJson = payload,
            status = AgentEventStatus.PENDING.name,
            createdAt = 1L,
        )

        val anchor = findDeferredShellToolAnchors(conversation).firstOrNull { it.taskId == taskId }?.anchor
        val synthetic = buildSyntheticAgentEventMessage(event)

        assertNull("without a deferred tool carrying the taskId, the drain must use the synthetic fallback", anchor)
        assertEquals(MessageRole.ASSISTANT, synthetic.role)
        val syntheticTool = synthetic.parts.single() as UIMessagePart.Tool
        assertEquals(sanitizeSyntheticToolName(ShellCompletion.KIND), syntheticTool.toolName)
        assertEquals(payload, (syntheticTool.output.single() as UIMessagePart.Text).text)
    }
}
