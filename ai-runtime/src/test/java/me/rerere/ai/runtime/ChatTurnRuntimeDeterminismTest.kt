package me.rerere.ai.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ConversationReader
import me.rerere.ai.runtime.contract.ConversationSummary
import me.rerere.ai.runtime.contract.MemoryScope
import me.rerere.ai.runtime.contract.MemoryWriter
import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeGenerationLog
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.contract.TurnMessageTransforms
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.junit.Test

/**
 * Deterministic behavior-equivalence harness for the extracted [ChatTurnRuntime] (issue #243 §D).
 *
 * Pins the exact post-extraction behavior with no Android / network / device: a scripted
 * [FakeProvider] emits a text delta, a tool-call delta, then a final chunk carrying a [TokenUsage];
 * a [FixedRuntimeClock] makes the turn timestamp deterministic. The assertions lock:
 *  (a) the emitted [GenerationChunk.Messages] sequence — the reply text + tool call, the usage merge
 *      onto the last message, and the final `finishedAt` == the FIXED clock instant (this pins the
 *      `Clock.System`/`TimeZone.currentSystemDefault()` → [RuntimeClock] substitution); and
 *  (b) prompt-assembly order in the materialized SYSTEM message — system prompt FIRST, then the
 *      untrusted-tool clause ONLY when tools exist (a second variant with an empty tool pool asserts
 *      the clause is ABSENT), then recent chats — exactly the order the runtime builds.
 */
class ChatTurnRuntimeDeterminismTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val fixedInstant = Instant.parse("2026-01-02T03:04:05Z")
    private val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake")
    private val model = Model(modelId = "fake-model", displayName = "Fake", id = Uuid.random())

    private class FixedRuntimeClock(
        private val instant: Instant,
        private val zone: TimeZone,
    ) : RuntimeClock {
        override fun now(): Instant = instant
        override fun timeZone(): TimeZone = zone
    }

    /** Captures the messages the provider was actually sent — so we can assert on prompt assembly. */
    private class FakeProvider(
        private val script: List<MessageChunk>,
    ) : Provider<ProviderSetting> {
        var sentMessages: List<UIMessage>? = null
        var sentParams: TextGenerationParams? = null

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            sentMessages = messages
            sentParams = params
            return script.first()
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> {
            sentMessages = messages
            sentParams = params
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

    /** Records the order in which the four transform hooks are invoked; otherwise identity. */
    private class RecordingTransforms(val calls: MutableList<String> = mutableListOf()) : TurnMessageTransforms {
        override suspend fun transformInput(messages: List<UIMessage>): List<UIMessage> {
            calls += "transformInput"; return messages
        }

        override suspend fun transformOutput(messages: List<UIMessage>): List<UIMessage> {
            calls += "transformOutput"; return messages
        }

        override suspend fun visualTransform(messages: List<UIMessage>): List<UIMessage> {
            calls += "visualTransform"; return messages
        }

        override suspend fun onGenerationFinish(messages: List<UIMessage>): List<UIMessage> {
            calls += "onGenerationFinish"; return messages
        }
    }

    private val noopGenerationLog = RuntimeGenerationLog { _, _, _, _ -> }

    private fun emptyConversationReader() = object : ConversationReader {
        override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> = emptyList()
    }

    private fun assistant(
        systemPrompt: String = "You are helpful.",
        recentChats: Boolean = false,
    ): AssistantConfig = AssistantConfig(
        id = Uuid.random(),
        chatModelId = null,
        systemPrompt = systemPrompt,
        streamOutput = true,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = recentChats,
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
    )

    private fun chunk(vararg parts: UIMessagePart, usage: TokenUsage? = null): MessageChunk = MessageChunk(
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
        usage = usage,
    )

    private fun seed() = listOf(
        UIMessage.user("hello"),
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
    )

    private fun runtime(
        provider: Provider<ProviderSetting>,
        reader: ConversationReader,
        logSink: RecordingLogSink = RecordingLogSink(),
    ): ChatTurnRuntime =
        ChatTurnRuntime(
            json = json,
            resolver = FixedResolver(provider),
            memoryWriter = noopMemoryWriter,
            conversationReader = reader,
            clock = FixedRuntimeClock(fixedInstant, TimeZone.UTC),
            logSink = logSink,
            generationLog = noopGenerationLog,
        )

    @Test
    fun `streamed text + usage merge + deterministic finishedAt`() = runBlocking {
        // A single text-delta turn carrying a final usage; no tools => the loop runs exactly one step.
        val fake = FakeProvider(
            listOf(
                chunk(UIMessagePart.Text("Hello ")),
                chunk(UIMessagePart.Text("world"), usage = TokenUsage(promptTokens = 7, completionTokens = 3)),
            )
        )

        val chunks = runtime(fake, emptyConversationReader()).run(
            turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
            model = model,
            messages = seed(),
            assistant = assistant(),
            transforms = identityTransforms,
        ).toList().filterIsInstance<GenerationChunk.Messages>()

        val finalMessages = chunks.last().messages
        val last = finalMessages.last()

        // Reply text assembled from the two deltas.
        assertEquals("Hello world", last.toText())

        // Usage merged onto the LAST message (promptTokens 7 + completionTokens 3 => totalTokens 10).
        assertEquals(TokenUsage(promptTokens = 7, completionTokens = 3, totalTokens = 10), last.usage)

        // finishedAt is stamped from the FIXED clock — NOT system time. This pins the
        // Clock.System/TimeZone.currentSystemDefault() -> RuntimeClock substitution.
        assertEquals(fixedInstant.toLocalDateTime(TimeZone.UTC), last.finishedAt)
    }

    @Test
    fun `system prompt assembled first, untrusted-tool clause present only with tools, recent chats appended`() = runBlocking {
        val reader = object : ConversationReader {
            override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> =
                listOf(ConversationSummary(Uuid.random(), assistantId, title = "About cats", lastChatDate = "Jan 1, 2026"))
        }

        // No tools — the untrusted-tool clause must be ABSENT.
        val noToolProvider = FakeProvider(listOf(chunk(UIMessagePart.Text("ok"))))
        runtime(noToolProvider, reader).run(
            turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
            model = model,
            messages = seed(),
            assistant = assistant(systemPrompt = "SYSTEM_MARKER", recentChats = true),
            transforms = identityTransforms,
        ).toList()

        val systemNoTools = noToolProvider.sentMessages!!.first { it.role == MessageRole.SYSTEM }.toText()
        assertTrue("system prompt present", systemNoTools.contains("SYSTEM_MARKER"))
        assertFalse("no untrusted-tool clause without tools", systemNoTools.contains("untrusted DATA"))
        assertTrue("recent chats appended", systemNoTools.contains("Recent Chats"))
        assertTrue("recent chats carry the rendered last_chat date", systemNoTools.contains("Jan 1, 2026"))
        // Order: the system prompt comes before the recent-chats block.
        assertTrue(systemNoTools.indexOf("SYSTEM_MARKER") < systemNoTools.indexOf("Recent Chats"))

        // With a tool — the untrusted-tool clause must be PRESENT, after the system prompt.
        val withToolProvider = FakeProvider(listOf(chunk(UIMessagePart.Text("ok"))))
        runtime(withToolProvider, emptyConversationReader()).run(
            turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
            model = model,
            messages = seed(),
            assistant = assistant(systemPrompt = "SYSTEM_MARKER"),
            transforms = identityTransforms,
            tools = listOf(me.rerere.ai.core.Tool(name = "search", description = "", execute = { emptyList() })),
        ).toList()

        val systemWithTools = withToolProvider.sentMessages!!.first { it.role == MessageRole.SYSTEM }.toText()
        assertTrue("untrusted-tool clause present with tools", systemWithTools.contains("untrusted DATA"))
        assertTrue(systemWithTools.indexOf("SYSTEM_MARKER") < systemWithTools.indexOf("untrusted DATA"))
    }

    @Test
    fun `tool schema budget exhaustion retries provider turn without tools`() = runBlocking {
        val fake = FakeProvider(listOf(chunk(UIMessagePart.Text("ok"))))
        val logSink = RecordingLogSink()
        val smallWindowModel = model.copy(contextWindow = 1_002)
        val hugeTool = Tool(
            name = "huge_schema_tool",
            description = "schema ".repeat(5_000),
            execute = { emptyList() },
        )

        runtime(fake, emptyConversationReader(), logSink).run(
            turn = TurnConfig(defaultModelId = smallWindowModel.id, providers = listOf(provider), assistants = emptyList()),
            model = smallWindowModel,
            messages = seed(),
            assistant = assistant(systemPrompt = "SYSTEM_MARKER"),
            transforms = identityTransforms,
            tools = listOf(hugeTool),
        ).toList()

        assertTrue(fake.sentParams!!.tools.isEmpty())
        val systemMessage = fake.sentMessages!!.first { it.role == MessageRole.SYSTEM }.toText()
        assertTrue(systemMessage.contains("SYSTEM_MARKER"))
        assertFalse(systemMessage.contains("untrusted DATA"))
        assertTrue(logSink.lines.any { it.level == "warn" && it.msg.contains("retrying without tools") })
    }

    @Test
    fun `irreducible over-budget payload fails before provider send`() = runBlocking {
        val fake = FakeProvider(listOf(chunk(UIMessagePart.Text("ok"))))
        val logSink = RecordingLogSink()
        val smallWindowModel = model.copy(contextWindow = 1_002)
        val irreducibleMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "oversized",
                    toolName = "oversized",
                    input = "x".repeat(20_000),
                )
            )
        )

        try {
            runtime(fake, emptyConversationReader(), logSink).run(
                turn = TurnConfig(defaultModelId = smallWindowModel.id, providers = listOf(provider), assistants = emptyList()),
                model = smallWindowModel,
                messages = listOf(irreducibleMessage),
                assistant = assistant(),
                transforms = identityTransforms,
            ).toList()
            fail("expected loop-safe budget failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("cannot satisfy loop-safe provider payload budget"))
        }

        assertNull(fake.sentMessages)
        assertTrue(logSink.lines.any { it.level == "error" && it.msg.contains("after fitting") })
    }

    /**
     * Pins the EXACT transformer invocation ORDER of the turn loop — the one behavior the post-hoc
     * review (#260) could only verify by inspection because [identityTransforms] above can't observe
     * reordering. For a single streamed chunk with no tools the loop runs once and must call, in this
     * order: [TurnMessageTransforms.transformInput] (over the assembled internal messages, before the
     * provider call) → [TurnMessageTransforms.transformOutput] then [TurnMessageTransforms.visualTransform]
     * (the streaming update, once per emitted chunk) → a final [TurnMessageTransforms.visualTransform]
     * then [TurnMessageTransforms.onGenerationFinish]. Reordering any of these (e.g. swapping the final
     * visual/finish pair, or running onGenerationFinish before the last output) reddens this test.
     */
    @Test
    fun `transformer hooks fire in input - repeated (output, visual) - visual - finish order`() = runBlocking {
        val recording = RecordingTransforms()
        // Exactly one streamed chunk => exactly one streaming update (one output/visual pair).
        val fake = FakeProvider(listOf(chunk(UIMessagePart.Text("ok"))))

        runtime(fake, emptyConversationReader()).run(
            turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
            model = model,
            messages = seed(),
            assistant = assistant(),
            transforms = recording,
        ).toList()

        assertEquals(
            listOf(
                "transformInput",      // assembled internal messages, before the provider call
                "transformOutput",     // streaming update (1 chunk => 1 pair)
                "visualTransform",
                "visualTransform",     // finalization: visual then finish
                "onGenerationFinish",
            ),
            recording.calls,
        )
    }
}
