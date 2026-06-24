package me.rerere.ai.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
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
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Issue #355 invariant at the runtime's provider-facing seam: only [Tool.advertised] tools are sent
 * to the provider (the tool schema) AND contribute their [Tool.systemPrompt] to the assembled system
 * message. A non-advertised tool — the legacy `task` spawn alias, carrying the SAME description and
 * the SAME subagent-registry systemPrompt as the canonical advertised `agent` — must therefore:
 *   (1) NOT appear in `params.tools` (no second interchangeable delegation tool), and
 *   (2) contribute its registry block ZERO extra times, so the block appears EXACTLY ONCE in the
 *       system prompt even though two tools declare it.
 *
 * This pins both #355 redundancies at the one place that turns tools into model input. A regression
 * that drops the `advertised` filter (or filters at the wrong layer) re-emits the duplicate registry
 * and re-advertises `task` — caught here, not only in the catalog-shape property test.
 */
class ChatTurnAdvertisedToolsTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake")
    private val model = Model(modelId = "fake-model", displayName = "Fake", id = Uuid.random())

    /** The production registry sentence shape; both spawn tools declare it via their systemPrompt. */
    private val registryBlock = "Available subagents you can run via the `agent` tool"

    private class FixedRuntimeClock : RuntimeClock {
        override fun now(): Instant = Instant.parse("2026-01-02T03:04:05Z")
        override fun timeZone(): TimeZone = TimeZone.UTC
    }

    /** Captures the [TextGenerationParams] and provider messages of the single generation call. */
    private class CapturingProvider(private val reply: List<MessageChunk>) : Provider<ProviderSetting> {
        var capturedTools: List<Tool>? = null
        var capturedMessages: List<UIMessage>? = null

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
            capturedTools = params.tools
            capturedMessages = messages
            return flow { reply.forEach { emit(it) } }
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

    private fun assistant(): AssistantConfig = AssistantConfig(
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
    )

    private fun runtime(impl: Provider<ProviderSetting>): ChatTurnRuntime = ChatTurnRuntime(
        json = json,
        resolver = FixedResolver(impl),
        memoryWriter = noopMemoryWriter,
        conversationReader = emptyReader,
        clock = FixedRuntimeClock(),
        logSink = RecordingLogSink(),
        generationLog = RuntimeGenerationLog { _, _, _, _ -> },
        hookDispatcher = null,
    )

    private fun textChunk(text: String): MessageChunk = MessageChunk(
        id = "test",
        model = "fake-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun spawnTool(name: String, advertised: Boolean): Tool = Tool(
        name = name,
        description = "Run a named subagent for a self-contained task when that subagent's " +
            "advertised description clearly matches the work.",
        systemPrompt = { _, _ -> registryBlock },
        advertised = advertised,
        execute = { emptyList() },
    )

    @Test
    fun `runtime advertises only advertised tools and emits the registry block once`() {
        // The catalog shape after #355: canonical `agent` advertised, legacy `task` resolvable but
        // hidden, both declaring the identical registry systemPrompt.
        val tools = listOf(
            spawnTool("agent", advertised = true),
            spawnTool("task", advertised = false),
        )
        val capturing = CapturingProvider(listOf(textChunk("done")))

        runBlocking {
            runtime(capturing).run(
                turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
                model = model,
                messages = listOf(
                    UIMessage.user("hi"),
                    UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
                ),
                assistant = assistant(),
                transforms = identityTransforms,
                tools = tools,
            ).toList()
        }

        val advertisedNames = capturing.capturedTools?.map { it.name } ?: emptyList()
        assertTrue("canonical `agent` is advertised to the provider", advertisedNames.contains("agent"))
        assertFalse("legacy `task` alias is NOT advertised to the provider", advertisedNames.contains("task"))

        val systemText = capturing.capturedMessages
            ?.filter { it.role == MessageRole.SYSTEM }
            ?.joinToString("\n") { it.toText() }
            .orEmpty()
        val occurrences = Regex(Regex.escape(registryBlock)).findAll(systemText).count()
        assertEquals(
            "subagent registry block must appear exactly once even though two tools declare it",
            1,
            occurrences,
        )
    }
}
