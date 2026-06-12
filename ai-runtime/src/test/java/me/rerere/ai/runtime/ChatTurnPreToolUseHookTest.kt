package me.rerere.ai.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.runtime.contract.ConversationReader
import me.rerere.ai.runtime.contract.ConversationSummary
import me.rerere.ai.runtime.contract.MemoryScope
import me.rerere.ai.runtime.contract.MemoryWriter
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeGenerationLog
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.contract.TurnMessageTransforms
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.ai.runtime.hooks.isDeniedByHook
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ORIGINAL_INPUT = """{"q":"original"}"""
private const val REWRITTEN_INPUT = """{"q":"rewritten"}"""

/**
 * PreToolUse fire-point wiring (#200 T7, H2): the turn loop dispatches PreToolUse for each fresh
 * tool call and feeds the aggregated decision through the EXISTING [ToolApprovalState] machine —
 * Deny → Denied(reason), Ask → Pending, Allow + `updatedInput` → `tool.copy(input = …)` applied
 * BEFORE the `needsApproval` gate. Ordering is asserted via state, not interactions: a
 * needs-approval tool that ends up Pending must already carry the REWRITTEN input, which is only
 * possible if the rewrite ran before the gate (approval resolves by toolCallId, so a post-gate
 * rewrite would show the user stale input — the H2 security hole).
 */
class ChatTurnPreToolUseHookTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake")
    private val model = Model(modelId = "fake-model", displayName = "Fake", id = Uuid.random())

    private class FixedRuntimeClock : RuntimeClock {
        override fun now(): Instant = Instant.parse("2026-01-02T03:04:05Z")
        override fun timeZone(): TimeZone = TimeZone.UTC
    }

    /** Pops ONE script per generation call, so multi-step turns are deterministic. */
    private class ScriptedProvider(
        scripts: List<List<MessageChunk>>,
    ) : Provider<ProviderSetting> {
        private val remaining = ArrayDeque(scripts)
        var generationCalls = 0

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> {
            generationCalls++
            val script = remaining.removeFirst()
            return flow { script.forEach { emit(it) } }
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow {}

        override suspend fun editImage(
            providerSetting: ProviderSetting,
            params: ImageEditParams,
        ): Flow<ImageGenerationItem> = flow {}

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("unused")
    }

    private inner class FixedResolver(private val impl: Provider<ProviderSetting>) : ModelProviderResolver {
        override fun findModel(modelId: Uuid, turn: TurnConfig): Model? = model
        override fun findProvider(model: Model, turn: TurnConfig): ProviderSetting? = provider
        override fun provider(setting: ProviderSetting): Provider<*> = impl
    }

    private val noopMemoryWriter = object : MemoryWriter {
        override suspend fun add(scope: MemoryScope, content: String): AssistantMemory = AssistantMemory(0, content)
        override suspend fun update(id: Int, content: String): AssistantMemory = AssistantMemory(id, content)
        override suspend fun delete(id: Int) {}
    }

    private val identityTransforms = object : TurnMessageTransforms {
        override suspend fun transformInput(messages: List<UIMessage>): List<UIMessage> = messages
        override suspend fun transformOutput(messages: List<UIMessage>): List<UIMessage> = messages
        override suspend fun visualTransform(messages: List<UIMessage>): List<UIMessage> = messages
        override suspend fun onGenerationFinish(messages: List<UIMessage>): List<UIMessage> = messages
    }

    private val emptyReader = object : ConversationReader {
        override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> = emptyList()
    }

    /** Returns a canned hook response and records every invocation. */
    private class CannedHookExecutor(private val response: String) : HookExecutor {
        val invocations = mutableListOf<Pair<HookEvent, String>>()
        override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String {
            invocations += event to input
            return response
        }
    }

    private fun dispatcher(executor: HookExecutor): HookDispatcher = HookDispatcher(
        executors = mapOf(HookHandler.Llm::class to executor),
        logSink = RecordingLogSink(),
    )

    private fun preToolUseHooks(): HookConfig = HookConfig(
        hooks = mapOf(
            HookEvent.PreToolUse to listOf(
                HookMatcher(matcher = null, handlers = listOf(HookHandler.Llm(prompt = "gate"))),
            ),
        ),
        trusted = true,
    )

    private fun assistant(hooks: HookConfig = HookConfig()): AssistantConfig = AssistantConfig(
        id = Uuid.random(),
        chatModelId = null,
        systemPrompt = "You are helpful.",
        streamOutput = true,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        messageTemplate = "{{ message }}",
        regexes = emptyList(),
        reasoningLevel = ReasoningLevel.AUTO,
        allowConversationSystemPrompt = false,
        temperature = null,
        topP = null,
        contextMessageSize = 0,
        maxTokens = null,
        customHeaders = emptyList(),
        customBodies = emptyList(),
        mcpServers = emptySet(),
        localToolIds = emptyList(),
        enabledSkills = emptySet(),
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        knowledgeBaseId = null,
        description = "",
        spawnable = false,
        subagentMaxSteps = null,
        hooks = hooks,
    )

    private fun runtime(provider: Provider<ProviderSetting>, hookDispatcher: HookDispatcher?): ChatTurnRuntime =
        ChatTurnRuntime(
            json = json,
            resolver = FixedResolver(provider),
            memoryWriter = noopMemoryWriter,
            conversationReader = emptyReader,
            clock = FixedRuntimeClock(),
            logSink = RecordingLogSink(),
            generationLog = RuntimeGenerationLog { _, _, _, _ -> },
            hookDispatcher = hookDispatcher,
        )

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "test",
        model = "fake-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun toolCallChunk(): MessageChunk = chunk(
        UIMessagePart.Tool(toolCallId = "call_1", toolName = "search", input = ORIGINAL_INPUT),
    )

    private fun seed() = listOf(
        UIMessage.user("hello"),
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
    )

    private fun run(
        scripted: ScriptedProvider,
        hookDispatcher: HookDispatcher?,
        assistant: AssistantConfig,
        tools: List<Tool>,
    ): List<UIMessage> = runBlocking {
        runtime(scripted, hookDispatcher).run(
            turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
            model = model,
            messages = seed(),
            assistant = assistant,
            transforms = identityTransforms,
            tools = tools,
        ).toList().filterIsInstance<GenerationChunk.Messages>().last().messages
    }

    private fun List<UIMessage>.toolPart(): UIMessagePart.Tool =
        flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().single()

    @Test
    fun `deny hook blocks the tool via the existing Denied state with the hook reason`() {
        val executor = CannedHookExecutor(
            """{"hookEventName":"PreToolUse","decision":"deny","reason":"blocked by hook policy"}"""
        )
        var executeCalls = 0
        val toolDef = Tool(name = "search", description = "", execute = {
            executeCalls++
            listOf(UIMessagePart.Text("result"))
        })
        // Step 1 emits the tool call; the denied result loops back for step 2's plain-text close.
        val provider = ScriptedProvider(listOf(listOf(toolCallChunk()), listOf(chunk(UIMessagePart.Text("done")))))

        val messages = run(provider, dispatcher(executor), assistant(preToolUseHooks()), listOf(toolDef))

        val tool = messages.toolPart()
        assertEquals(ToolApprovalState.Denied("blocked by hook policy"), tool.approvalState)
        assertEquals("tool body must never run on deny", 0, executeCalls)
        // The denial flows through the EXISTING denied path: the result the model sees carries the reason.
        val output = (tool.output.single() as UIMessagePart.Text).text
        assertTrue("denied result must surface the hook reason", output.contains("blocked by hook policy"))
        // Provenance marker (#200 T10): the UI must be able to badge this as "blocked by hook",
        // distinguishable from a user denial — never silent.
        assertTrue("hook denial must carry the provenance marker", isDeniedByHook(tool.metadata))
    }

    @Test
    fun `ask hook routes the tool to the existing Pending HITL state`() {
        val executor = CannedHookExecutor("""{"decision":"ask"}""")
        var executeCalls = 0
        val toolDef = Tool(name = "search", description = "", needsApproval = false, execute = {
            executeCalls++
            listOf(UIMessagePart.Text("result"))
        })
        val provider = ScriptedProvider(listOf(listOf(toolCallChunk())))

        val messages = run(provider, dispatcher(executor), assistant(preToolUseHooks()), listOf(toolDef))

        val tool = messages.toolPart()
        assertEquals(ToolApprovalState.Pending, tool.approvalState)
        assertFalse("tool must wait for the user, not execute", tool.isExecuted)
        assertEquals(0, executeCalls)
        assertEquals("loop must break and wait for the user", 1, provider.generationCalls)
    }

    @Test
    fun `allow with updatedInput rewrites the input BEFORE the needsApproval gate`() {
        val executor = CannedHookExecutor(
            """{"decision":"allow","updatedInput":${json.encodeToString(REWRITTEN_INPUT)}}"""
        )
        val toolDef = Tool(name = "search", description = "", needsApproval = true, execute = {
            listOf(UIMessagePart.Text("result"))
        })
        val provider = ScriptedProvider(listOf(listOf(toolCallChunk())))

        val messages = run(provider, dispatcher(executor), assistant(preToolUseHooks()), listOf(toolDef))

        val tool = messages.toolPart()
        // Ordering, asserted via state: the Pending tool the user is asked to approve already
        // carries the rewritten input — impossible if the rewrite ran after the approval gate.
        assertEquals(ToolApprovalState.Pending, tool.approvalState)
        assertEquals(REWRITTEN_INPUT, tool.input)
        assertFalse(tool.isExecuted)
    }

    @Test
    fun `allow with updatedInput executes the rewritten input when no approval is needed`() {
        val executor = CannedHookExecutor(
            """{"decision":"allow","updatedInput":${json.encodeToString(REWRITTEN_INPUT)}}"""
        )
        val executedArgs = mutableListOf<JsonElement>()
        val toolDef = Tool(name = "search", description = "", execute = { args ->
            executedArgs += args
            listOf(UIMessagePart.Text("result"))
        })
        val provider = ScriptedProvider(listOf(listOf(toolCallChunk()), listOf(chunk(UIMessagePart.Text("done")))))

        val messages = run(provider, dispatcher(executor), assistant(preToolUseHooks()), listOf(toolDef))

        assertEquals(REWRITTEN_INPUT, messages.toolPart().input)
        assertEquals(json.parseToJsonElement(REWRITTEN_INPUT), executedArgs.single())
    }

    @Test
    fun `no hooks configured leaves the loop unchanged and never calls an executor`() {
        val executor = CannedHookExecutor("""{"decision":"deny","reason":"must not be reached"}""")
        val executedArgs = mutableListOf<JsonElement>()
        val toolDef = Tool(name = "search", description = "", execute = { args ->
            executedArgs += args
            listOf(UIMessagePart.Text("result"))
        })
        val provider = ScriptedProvider(listOf(listOf(toolCallChunk()), listOf(chunk(UIMessagePart.Text("done")))))

        val messages = run(provider, dispatcher(executor), assistant(hooks = HookConfig()), listOf(toolDef))

        assertEquals(ORIGINAL_INPUT, messages.toolPart().input)
        assertEquals(ToolApprovalState.Auto, messages.toolPart().approvalState)
        assertEquals(json.parseToJsonElement(ORIGINAL_INPUT), executedArgs.single())
        assertTrue("no executor call without configured hooks", executor.invocations.isEmpty())
    }
}
