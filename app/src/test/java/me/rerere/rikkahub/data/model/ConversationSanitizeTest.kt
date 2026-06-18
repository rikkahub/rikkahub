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

    // case (b): an orphaned/unexecuted tool with no resumable approval must be
    // balanced in place (synthetic tool_result), never uploaded as an unbalanced
    // tool_use. A tool-only node is retained (and balanced), not dropped.
    @Test
    fun `orphaned unexecuted tool node is retained and balanced`() {
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

        // The real invariant: no unbalanced orphan tool survives.
        assertTrue(
            "no unexecuted, non-resumable tool may be uploaded",
            sent.flatMap { it.getTools() }
                .none { !it.isExecuted && !it.approvalState.canResumeToolExecution() }
        )
    }

    // FIX B regression: an interrupted assistant turn that carries BOTH text and an
    // orphan empty-output tool must keep its text (the drop discarded it) and the
    // tool must be balanced.
    @Test
    fun `orphan empty-output tool preserves assistant text and balances the tool`() {
        val partial = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("here is my almost-complete answer"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "search",
                    input = "{}",
                    output = emptyList()
                ),
            )
        )
        val nodes = listOf(
            userNode("do something"),
            partial.toMessageNode(),
        )

        val sanitized = nodes.sanitizeForUpload()
        val sent = sanitized.map { it.currentMessage }

        // BEFORE the fix: the whole branch was dropped -> the node disappeared and
        // the assistant text was lost.
        assertEquals("the assistant node must be retained", 2, sanitized.size)
        val sentText = sent.joinToString("\n") { it.toText() }
        assertTrue(
            "the assistant's text must not be lost",
            sentText.contains("here is my almost-complete answer")
        )
        assertTrue(
            "the orphan tool must be balanced (no unbalanced tool_use survives)",
            sent.flatMap { it.getTools() }
                .none { !it.isExecuted && !it.approvalState.canResumeToolExecution() }
        )
    }

    // Approval invariant: a legitimately Pending tool (awaiting user approval) must
    // be kept untouched — output stays empty, state stays Pending — so the approval
    // / resume UI path is unchanged.
    @Test
    fun `pending-approval tool with text is kept and not repaired`() {
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("let me check that for you"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "search",
                    input = "{}",
                    output = emptyList(),
                    approvalState = me.rerere.ai.ui.ToolApprovalState.Pending
                ),
            )
        )
        val nodes = listOf(
            userNode("do something"),
            pending.toMessageNode(),
        )

        val sanitized = nodes.sanitizeForUpload()
        val tools = sanitized.flatMap { it.currentMessage.getTools() }

        assertEquals("the pending tool must be retained", 1, tools.size)
        val tool = tools.single()
        assertTrue("a pending tool must stay unexecuted", tool.output.isEmpty())
        assertTrue(
            "a pending tool must keep its Pending state",
            tool.approvalState is me.rerere.ai.ui.ToolApprovalState.Pending
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

    // STOP_IS_DETACH_NOT_KILL (issue #291): a detach-opted, auto-approved workspace_shell left with
    // empty output by a user Stop is a STILL-ALIVE backgrounded run, not a cancelled one. Stamping
    // {status:cancelled} over it (the unfixed repairOrphanTools) lies about a running process. The
    // honest marker is {status:running} — byte-identical to the shipped Detached path — so both
    // order-independent finalizers (repairOrphanTools and ChatService.cancelToolByUser) agree.
    private fun shellTool(input: String, executed: Boolean = false) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "workspace_shell",
                input = input,
                output = if (executed) listOf(UIMessagePart.Text("{}")) else emptyList(),
                approvalState = me.rerere.ai.ui.ToolApprovalState.Auto,
            )
        )
    ).toMessageNode()

    private fun shellOutputText(sanitized: List<MessageNode>): String =
        (sanitized.flatMap { it.currentMessage.getTools() }.single().output.single() as UIMessagePart.Text).text

    @Test
    fun `backgroundable shell orphan is stamped status running not cancelled`() {
        val nodes = listOf(
            userNode("run it"),
            shellTool("""{"command":"sleep 999","detachAfterSeconds":30}"""),
        )

        val sanitized = nodes.sanitizeForUpload()
        val shell = sanitized.flatMap { it.currentMessage.getTools() }.single()

        // FAILS-BEFORE: the unfixed repairOrphanTools stamps the cancelled marker over the empty
        // backgroundable shell, so the tool's synthetic output contains "cancelled".
        assertFalse(
            "a still-alive backgrounded shell must not be stamped cancelled",
            shellOutputText(sanitized).contains("cancelled"),
        )
        assertTrue("the shell is made executed (balanced) by the running marker", shell.isExecuted)
        assertFalse("taskId-less finalizer markers are not anchor-resolvable and must not be deferred", shell.isDeferred)
        assertEquals(
            "the marker is the honest {status:running}",
            SHELL_BACKGROUNDED_MARKER,
            shellOutputText(sanitized),
        )
    }

    @Test
    fun `backgroundable shell sanitize is idempotent`() {
        val nodes = listOf(
            userNode("run it"),
            shellTool("""{"command":"sleep 999","detachAfterSeconds":30}"""),
        )

        val once = nodes.sanitizeForUpload()
        val twice = once.sanitizeForUpload()

        assertEquals(
            once.map { it.currentMessage.toText() },
            twice.map { it.currentMessage.toText() },
        )
        val onceShell = once.flatMap { it.currentMessage.getTools() }.single()
        val twiceShell = twice.flatMap { it.currentMessage.getTools() }.single()
        assertEquals(onceShell.output, twiceShell.output)
    }

    @Test
    fun `adversarial shell input does not crash sanitizeForUpload`() {
        // The orphaned workspace_shell's input is MODEL-controlled. Valid-but-non-object JSON ("[]")
        // routed through repairOrphanTools (sanitizeForUpload) crashed at isBackgroundableShell's
        // throwing .jsonObject — taking down the whole conversation's upload finalization instead of
        // repairing the orphan. FAILS-BEFORE with IllegalArgumentException; after the fix the non-object
        // input simply reads as non-backgroundable, so the orphan is stamped cancelled and balanced.
        val nodes = listOf(
            userNode("run it"),
            shellTool("[]"),
        )

        val sanitized = nodes.sanitizeForUpload()
        val shell = sanitized.flatMap { it.currentMessage.getTools() }.single()
        assertTrue("the orphan is repaired, not crashed", shell.isExecuted)
        assertEquals(
            "non-object input is non-backgroundable, so the orphan is cancelled",
            TOOL_CANCELLED_MARKER,
            shellOutputText(sanitized),
        )
    }

    @Test
    fun `non-detach shell orphan is still stamped cancelled`() {
        // A default-kill shell (no detachAfterSeconds) is NOT backgrounded: no completion event ever
        // arrives, so it must still be finalized as cancelled — not left as a phantom running run.
        val nodes = listOf(
            userNode("run it"),
            shellTool("""{"command":"echo hi"}"""),
        )

        val sanitized = nodes.sanitizeForUpload()
        val output = shellOutputText(sanitized)
        assertTrue("a non-detach shell orphan is still cancelled", output.contains("cancelled"))
        assertEquals(
            "a non-detach shell must not become a phantom running run",
            TOOL_CANCELLED_MARKER,
            output,
        )
    }

    @Test
    fun `isBackgroundableShell truth table`() {
        fun tool(name: String, input: String) = UIMessagePart.Tool(
            toolCallId = "c", toolName = name, input = input, output = emptyList(),
        )
        assertTrue(tool("workspace_shell", """{"detachAfterSeconds":30}""").isBackgroundableShell())
        assertFalse(tool("workspace_shell", """{"detachAfterSeconds":0}""").isBackgroundableShell())
        assertFalse(tool("workspace_shell", """{"command":"echo hi"}""").isBackgroundableShell())
        assertFalse(tool("workspace_shell", "").isBackgroundableShell())
        assertFalse(tool("web_search", """{"detachAfterSeconds":30}""").isBackgroundableShell())
        // ADVERSARIAL model-controlled input: valid-but-non-object JSON and a non-primitive
        // detachAfterSeconds must read as non-backgroundable WITHOUT throwing. The throwing
        // .jsonObject/.jsonPrimitive accessors would crash repairOrphanTools (sanitizeForUpload) here.
        assertFalse(tool("workspace_shell", "[]").isBackgroundableShell())
        assertFalse(tool("workspace_shell", "1").isBackgroundableShell())
        assertFalse(tool("workspace_shell", "\"x\"").isBackgroundableShell())
        assertFalse(tool("workspace_shell", """{"detachAfterSeconds":{}}""").isBackgroundableShell())
        assertFalse(tool("workspace_shell", """{"detachAfterSeconds":[30]}""").isBackgroundableShell())
        // LENIENT model JSON: the runtime executes tool args through a lenient parser (unquoted keys),
        // so the sanitizer must classify the SAME input identically. A strict parse falls back to {} and
        // would misclassify these genuinely-detached runs as non-backgroundable → {status:cancelled}
        // stamped over a live process. These FAIL before the lenient-parse fix.
        assertTrue(
            "unquoted keys (lenient) must still read as backgroundable",
            tool("workspace_shell", """{command:"sleep 999",detachAfterSeconds:30}""").isBackgroundableShell(),
        )
        assertTrue(
            "detachAfterSeconds as a quoted numeric string must read as backgroundable",
            tool("workspace_shell", """{"detachAfterSeconds":"30"}""").isBackgroundableShell(),
        )
        assertTrue(
            "unquoted key with quoted numeric value must read as backgroundable",
            tool("workspace_shell", """{detachAfterSeconds:"45"}""").isBackgroundableShell(),
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
