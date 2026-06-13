package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The child-approval round-trip wiring (SPEC.md M4 / Gap A): the pool rewrite
 * ([gateSubagentTools]), the suspension rendezvous ([PendingChildApprovals]), the visibility
 * transcript edits ([injectChildApprovalPart] / [resolveChildApprovalPart]), and the namespaced-id
 * classifier `handleToolApproval` branches on. Each is pure/JVM so the whole round-trip is pinned
 * without ChatService or Android.
 */
class ChildApprovalRoundTripTest {

    // --- gateSubagentTools --------------------------------------------------------------------

    private fun tool(name: String, needsApproval: Boolean, onExecute: () -> Unit = {}): Tool =
        Tool(name = name, description = name, needsApproval = needsApproval, execute = {
            onExecute()
            listOf(UIMessagePart.Text("$name ran"))
        })

    private class RecordingGate(private val decide: TaskApprovalDecision) : TaskApprovalGate {
        val requests = mutableListOf<Pair<Uuid, TaskApprovalRequest>>()
        override suspend fun await(taskId: Uuid, request: TaskApprovalRequest): TaskApprovalDecision {
            requests += taskId to request
            return decide
        }
    }

    @Test
    fun `non-approval tools pass through as the same instance`() {
        val plain = tool("search", needsApproval = false)
        val gated = gateSubagentTools(listOf(plain), Uuid.random(), RecordingGate(TaskApprovalDecision.Approved))
        assertSame("a needsApproval=false tool must not be rewritten", plain, gated.single())
    }

    @Test
    fun `approval tools are rewritten gate-first with needsApproval off`(): Unit = runBlocking {
        var executed = 0
        val gate = RecordingGate(TaskApprovalDecision.Approved)
        val taskId = Uuid.random()
        val gated = gateSubagentTools(
            listOf(tool("ask_user", needsApproval = true) { executed++ }),
            taskId,
            gate,
        ).single()

        assertFalse("the child runtime must never gate the tool itself", gated.needsApproval)

        val output = gated.execute(buildJsonObject { put("q", "ok?") })

        assertEquals("approval precedes execution, exactly once", 1, gate.requests.size)
        val (forTask, request) = gate.requests.single()
        assertEquals(taskId, forTask)
        assertEquals("ask_user", request.toolName)
        assertTrue("the request carries the call arguments for the parent to judge",
            request.argumentsJson.contains("ok?"))
        assertEquals("approved -> the real tool ran", 1, executed)
        assertEquals("ask_user ran", (output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a denied tool never executes and resumes the child with the denial result`(): Unit = runBlocking {
        var executed = 0
        val gated = gateSubagentTools(
            listOf(tool("dangerous", needsApproval = true) { executed++ }),
            Uuid.random(),
            RecordingGate(TaskApprovalDecision.Denied("not in this house")),
        ).single()

        val output = gated.execute(buildJsonObject { put("x", 1) })

        assertEquals("denied -> the real tool must NOT run", 0, executed)
        assertEquals(
            listOf(deniedChildToolResult("dangerous", "not in this house")),
            output.map { (it as UIMessagePart.Text).text },
        )
    }

    @Test
    fun `an answered tool never executes - the answer IS the tool result`(): Unit = runBlocking {
        // ask_user-class tools: needsApproval=true and execute deliberately throws because the
        // HITL answer replaces execution. An Answered decision must therefore short-circuit.
        val askUser = Tool(name = "ask_user", description = "ask", needsApproval = true, execute = {
            error("ask_user tool should be handled by HITL flow")
        })
        val gated = gateSubagentTools(
            listOf(askUser),
            Uuid.random(),
            RecordingGate(TaskApprovalDecision.Answered("yes, proceed")),
        ).single()

        val output = gated.execute(buildJsonObject { put("question", "may I?") })

        assertEquals(
            "the user's answer must come back as the tool result, without executing the tool",
            listOf("yes, proceed"),
            output.map { (it as UIMessagePart.Text).text },
        )
    }

    @Test
    fun `each invocation mints a distinct childToolCallId`(): Unit = runBlocking {
        val gate = RecordingGate(TaskApprovalDecision.Approved)
        val gated = gateSubagentTools(
            listOf(tool("ask_user", needsApproval = true)),
            Uuid.random(),
            gate,
        ).single()

        gated.execute(buildJsonObject { })
        gated.execute(buildJsonObject { })

        val ids = gate.requests.map { it.second.childToolCallId }
        assertEquals("two calls, two distinct ids", 2, ids.toSet().size)
    }

    // --- PendingChildApprovals ------------------------------------------------------------------

    @Test
    fun `await suspends until resolve delivers the decision`(): Unit = runBlocking {
        val pending = PendingChildApprovals()
        pending.register("task/call-1")
        val decision = async(Dispatchers.Default) { pending.await("task/call-1") }

        assertTrue("a live waiter must be resumed", pending.resolve("task/call-1", TaskApprovalDecision.Approved))
        assertEquals("the waiter receives the parent's decision", TaskApprovalDecision.Approved, decision.await())
        assertFalse("the entry is removed once resolved", pending.isPending("task/call-1"))
    }

    @Test
    fun `a decision arriving between register and await is not lost`(): Unit = runBlocking {
        // The mustFix race: the pending part is user-visible the moment it is saved, so the
        // decision can land BEFORE the surface reaches await. Registration first means that
        // early decision completes the deferred and await returns immediately — it must never
        // be dropped, stranding the child on a decision the user already made.
        val pending = PendingChildApprovals()
        pending.register("task/early")
        assertTrue("an early decision must find the registered entry", pending.resolve("task/early", TaskApprovalDecision.Approved))
        assertEquals("await must return the already-delivered decision", TaskApprovalDecision.Approved, pending.await("task/early"))
        assertFalse(pending.isPending("task/early"))
    }

    @Test
    fun `abandon drops a registered entry that will never be awaited`() {
        val pending = PendingChildApprovals()
        pending.register("task/never-visible")
        pending.abandon("task/never-visible")
        assertFalse(pending.isPending("task/never-visible"))
        assertFalse("a decision after abandon is a no-op", pending.resolve("task/never-visible", TaskApprovalDecision.Approved))
    }

    @Test
    fun `resolving an unknown or dead id is a recorded no-op`(): Unit = runBlocking {
        val pending = PendingChildApprovals()
        assertFalse("no waiter -> false, never an invented approval", pending.resolve("task/ghost", TaskApprovalDecision.Approved))

        pending.register("task/cancelled")
        // UNDISPATCHED: the waiter runs to its first suspension (the deferred await) before
        // launch returns, so the cancellation below deterministically strikes a LIVE waiter.
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.await("task/cancelled")
        }
        waiter.cancelAndJoin()

        assertFalse("cancellation must clean the entry up", pending.isPending("task/cancelled"))
        assertFalse("a decision for a dead waiter is a no-op", pending.resolve("task/cancelled", TaskApprovalDecision.Approved))
    }

    // --- namespaced-id classifier ---------------------------------------------------------------

    @Test
    fun `only taskId-slash-child ids classify as child approvals`() {
        val namespaced = TaskApprovalRouter.namespacedToolCallId(Uuid.random(), "call_abc")
        assertTrue(TaskApprovalRouter.isNamespacedChildApprovalId(namespaced))
        assertFalse("provider parent ids must not classify", TaskApprovalRouter.isNamespacedChildApprovalId("call_abc"))
        assertFalse("a slash without a task UUID prefix must not classify",
            TaskApprovalRouter.isNamespacedChildApprovalId("not-a-uuid/call_abc"))
        assertFalse(TaskApprovalRouter.isNamespacedChildApprovalId(""))
    }

    // --- transcript visibility edits -------------------------------------------------------------

    private val runningTaskPart = UIMessagePart.Tool(
        toolCallId = "call_task_1",
        toolName = SPAWN_TOOL_NAME,
        input = """{"subagent":"Researcher","prompt":"go"}""",
        output = emptyList(), // unexecuted = the spawn is suspended right now
    )

    private fun conversationWithRunningTask(): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = listOf(
            UIMessage.user("do the thing").toMessageNode(),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(runningTaskPart)).toMessageNode(),
        ),
    )

    @Test
    fun `inject anchors a pending part inside the message holding the running task step`() {
        val namespacedId = "${Uuid.random()}/call-1"
        val injected = injectChildApprovalPart(
            conversation = conversationWithRunningTask(),
            namespacedToolCallId = namespacedId,
            toolName = "ask_user",
            argumentsJson = """{"q":"may I?"}""",
        )

        assertNotNull("a visible running task step is a valid anchor", injected)
        val anchored = injected!!.messageNodes.last().currentMessage.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == namespacedId }
        assertTrue("the injected part must be pending", anchored.isPending)
        assertEquals("ask_user", anchored.toolName)
        assertEquals("""{"q":"may I?"}""", anchored.input)
    }

    @Test
    fun `inject is idempotent and fails closed without a running task step`() {
        val namespacedId = "${Uuid.random()}/call-1"
        val conversation = conversationWithRunningTask()
        val once = injectChildApprovalPart(conversation, namespacedId, "ask_user", "{}")!!
        assertSame("a second injection of the same id is the identity", once,
            injectChildApprovalPart(once, namespacedId, "ask_user", "{}"))

        val noAnchor = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(UIMessage.user("no task here").toMessageNode()),
        )
        assertNull(
            "no visible task step -> null, the caller must deny instead of suspending invisibly",
            injectChildApprovalPart(noAnchor, namespacedId, "ask_user", "{}"),
        )
    }

    @Test
    fun `resolve writes the decision and a self-contained output onto the pending part`() {
        val namespacedId = "${Uuid.random()}/call-1"
        val injected = injectChildApprovalPart(conversationWithRunningTask(), namespacedId, "ask_user", "{}")!!

        val resolved = resolveChildApprovalPart(injected, namespacedId, ToolApprovalState.Approved)

        val part = resolved.messageNodes.last().currentMessage.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == namespacedId }
        assertEquals(ToolApprovalState.Approved, part.approvalState)
        assertFalse("no longer pending", part.isPending)
        assertTrue("the resolved record is self-contained (isExecuted)", part.isExecuted)

        // The parent's own task part is untouched.
        val taskPart = resolved.messageNodes.last().currentMessage.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolName == SPAWN_TOOL_NAME }
        assertEquals(runningTaskPart, taskPart)
    }
}
