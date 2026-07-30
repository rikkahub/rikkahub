package me.rerere.rikkahub.data.ai.agent.context

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelCapabilityProfile
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.artifacts.FileToolArtifactStore
import me.rerere.rikkahub.data.artifacts.ToolArtifactLimits
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextGovernorTest {
    @Test
    fun `plan records every partition and conservative budget`() {
        val system = UIMessage.system("system")
        val memory = UIMessage.system("memory")
        val schema = UIMessage.system("schema prompt")
        val history = UIMessage.user("intent")
        val tool = UIMessage.assistant("").copy(parts = listOf(tool(output = "result")))

        val result = governor().preflight(
            request(
                messages = listOf(system, memory, schema, history, tool),
                systemIds = setOf(system.id),
                memoryIds = setOf(memory.id),
                schemaIds = setOf(schema.id),
                schemaDefinition = "name description parameters",
                contextWindow = 1_000,
                reserve = 200,
            ),
        )

        val usage = checkNotNull(result.plan.usage)
        val budget = checkNotNull(result.plan.budget)
        assertTrue(usage.systemTokens > 0)
        assertTrue(usage.memoryTokens > 0)
        assertTrue(usage.historyTokens > 0)
        assertTrue(usage.toolSchemaTokens > 0)
        assertTrue(usage.toolOutputTokens > 0)
        assertEquals(budget.contextWindowTokens, budget.systemTokens + budget.memoryTokens + budget.historyTokens +
            budget.toolSchemaTokens + budget.toolOutputTokens + budget.outputReserveTokens + budget.safetyMarginTokens)
    }

    @Test
    fun `tool schema definition is charged once when a matching helper message exists`() {
        val schema = UIMessage.system("schema")

        val result = governor().preflight(
            request(
                messages = listOf(schema, UIMessage.user("intent")),
                schemaIds = setOf(schema.id),
                schemaDefinition = "schema",
                contextWindow = 1_000,
                reserve = 100,
            ),
        )

        assertEquals("schema".length, checkNotNull(result.plan.usage).toolSchemaTokens)
    }

    @Test
    fun `keeps recent user intent and complete legacy tool pair while trimming old history`() {
        val old = UIMessage.user("o".repeat(100))
        @Suppress("DEPRECATION")
        val call = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("call", "read", "{}")),
        )
        @Suppress("DEPRECATION")
        val result = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(UIMessagePart.ToolResult("call", "read", JsonPrimitive("ok"), JsonPrimitive("{}"))),
        )
        val recentIntent = UIMessage.user("i".repeat(70))

        val planned = governor().preflight(
            request(
                messages = listOf(old, call, result, recentIntent),
                recentUserIds = setOf(recentIntent.id),
                contextWindow = 500,
                reserve = 100,
            ),
        )

        assertTrue(planned.messages.any { it.id == recentIntent.id })
        assertTrue(planned.messages.any { it.id == call.id })
        assertTrue(planned.messages.any { it.id == result.id })
        assertFalse(planned.messages.any { it.id == old.id })
        assertTrue(ContextPlanAction.TRIM_HISTORY in planned.plan.actions)
    }

    @Test
    fun `output reserve is clamped to profile max and missing profile uses safe defaults`() {
        val clamped = governor().preflight(
            request(
                messages = listOf(UIMessage.user("intent")),
                contextWindow = 1_000,
                reserve = 300,
                profile = ModelCapabilityProfile(contextWindowTokens = 1_000, maxOutputTokens = 120),
            ),
        )
        val fallback = governor().preflight(request(messages = listOf(UIMessage.user("intent")), profile = null))

        assertEquals(120, clamped.plan.reservedOutputTokens)
        assertEquals(16 * 1024, fallback.plan.contextWindowTokens)
        assertEquals(1024, fallback.plan.reservedOutputTokens)
        assertTrue(ConservativeTokenEstimator.estimateTextTokens("你") >= 1)
    }

    @Test
    fun `isolated child context cap can only reduce the model window`() {
        val capped = governor().preflight(
            request(
                messages = listOf(UIMessage.user("intent")),
                contextWindow = 4_096,
                reserve = 200,
                maxContextWindow = 1_024,
            ),
        )

        assertEquals(1_024, capped.plan.contextWindowTokens)
        assertEquals(200, capped.plan.reservedOutputTokens)
    }

    @Test
    fun `blocks deterministically when preserved system cannot fit`() {
        val blocked = governor().preflight(
            request(
                messages = listOf(UIMessage.system("s".repeat(200))),
                systemIds = emptySet(),
                contextWindow = 500,
                reserve = 100,
            ),
        )

        assertTrue(blocked.blocked)
        assertEquals(ContextPlanCode.CONTEXT_BUDGET_EXCEEDED, blocked.plan.errorCode)
        assertEquals(ContextPlanAction.BLOCKED.name, blocked.plan.action)
    }

    @Test
    fun `blocks when output reserve and safety margin leave no input capacity`() {
        val blocked = governor().preflight(
            request(
                messages = listOf(UIMessage.user("intent")),
                contextWindow = 300,
                reserve = 100,
            ),
        )

        assertTrue(blocked.blocked)
        assertEquals(ContextPlanCode.OUTPUT_RESERVE_EXCEEDS_CONTEXT, blocked.plan.errorCode)
    }

    @Test
    fun `large tool output is artifactized before history trimming and telemetry is content free`() {
        val root = Files.createTempDirectory("context-governor-test").toFile()
        val governor = governor(root)
        val toolMessage = UIMessage.assistant("").copy(parts = listOf(tool(output = "private-output-" + "x".repeat(200))))
        val intent = UIMessage.user("intent")

        val planned = governor.preflight(
            request(
                messages = listOf(toolMessage, intent),
                recentUserIds = setOf(intent.id),
                contextWindow = 700,
                reserve = 100,
                scope = ToolArtifactRunScope("assistant", "conversation", "run"),
            ),
        )

        val rewritten = planned.messages.first { it.id == toolMessage.id }.getTools().single().output.single() as UIMessagePart.Text
        assertTrue(rewritten.text.contains("artifactId:"))
        assertTrue(rewritten.text.contains("toolExecutionId:"))
        assertTrue(ContextPlanAction.ARTIFACT_TOOL_OUTPUT in planned.plan.actions)
        assertFalse(planned.plan.toString().contains("private-output"))
        assertNotNull(checkNotNull(planned.plan.budget))
        assertTrue(root.walkTopDown().any { it.extension == "bin" })
    }

    private fun governor(root: File = Files.createTempDirectory("context-governor-test").toFile()): ArtifactContextGovernor =
        ArtifactContextGovernor(
            FileToolArtifactStore(
                root,
                ToolArtifactLimits(inlineMaxBytes = 4_096, inlineMaxEstimatedTokens = 4_096, previewMaxBytes = 32),
            ),
            CharacterTokenEstimator,
        )

    private fun request(
        messages: List<UIMessage>,
        systemIds: Set<kotlin.uuid.Uuid> = emptySet(),
        memoryIds: Set<kotlin.uuid.Uuid> = emptySet(),
        schemaIds: Set<kotlin.uuid.Uuid> = emptySet(),
        recentUserIds: Set<kotlin.uuid.Uuid> = emptySet(),
        schemaDefinition: String = "",
        contextWindow: Int = 1_000,
        reserve: Int = 100,
        maxContextWindow: Int? = null,
        profile: ModelCapabilityProfile? = ModelCapabilityProfile(contextWindowTokens = contextWindow, maxOutputTokens = reserve),
        scope: ToolArtifactRunScope? = null,
    ) = ContextPreflightRequest(
        messages = messages,
        systemMessageIds = systemIds,
        memoryMessageIds = memoryIds,
        toolSchemaMessageIds = schemaIds,
        recentUserMessageIds = recentUserIds,
        toolSchemaDefinition = schemaDefinition,
        requestedOutputTokens = reserve,
        maxContextWindowTokens = maxContextWindow,
        capabilityProfile = profile,
        artifactRunScope = scope,
    )

    private fun tool(output: String) = UIMessagePart.Tool(
        toolCallId = "call",
        toolName = "read",
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
    )

    private object CharacterTokenEstimator : TokenEstimator {
        override fun estimateTextTokens(text: String): Int = text.length

        override fun estimateMessageTokens(message: UIMessage): Int = 10 + message.parts.sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> estimateTextTokens(part.text)
                is UIMessagePart.Tool -> estimateTextTokens(part.toolName) + estimateTextTokens(part.input) +
                    estimateToolOutputTokens(part.output)
                is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
                is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) + estimateTextTokens(part.content.toString())
                is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
                is UIMessagePart.Image -> estimateTextTokens(part.url)
                is UIMessagePart.Video -> estimateTextTokens(part.url)
                is UIMessagePart.Audio -> estimateTextTokens(part.url)
                is UIMessagePart.Document -> estimateTextTokens(part.url) + estimateTextTokens(part.fileName)
                is UIMessagePart.Search -> 1
            }
        }

        override fun estimateToolOutputTokens(output: List<UIMessagePart>): Int = output.sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> estimateTextTokens(part.text)
                else -> estimateTextTokens(part.toString())
            }
        }
    }
}
