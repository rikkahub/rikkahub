package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderInstances
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.runtime.contract.ConversationReader
import me.rerere.ai.runtime.contract.ConversationSummary
import me.rerere.ai.runtime.contract.MemoryScope
import me.rerere.ai.runtime.contract.MemoryWriter
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeLogSink
import me.rerere.ai.runtime.contract.TurnConfig
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
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Integration-shaped regression for the two production wiring breaks of #200 v1 (review findings
 * 1+2): the path goes app [Assistant] -> `toAssistantConfig()` -> the [ChatTurnRuntime] that
 * [GenerationHandler] itself composes — exactly the composition the shipped app runs, not a
 * hand-built runtime. A configured, trusted PreToolUse deny hook must block the tool through
 * `generateText`; this fails if GenerationHandler drops the dispatcher OR the mapper drops the
 * hooks field. The collaborators GenerationHandler never touches on this path (Android Context,
 * ProviderManager) are inert placeholders.
 */
class GenerationHandlerPreToolUseHookTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val providerSetting = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake")
    private val model = Model(modelId = "fake-model", displayName = "Fake", id = Uuid.random())

    private class FixedRuntimeClock : RuntimeClock {
        override fun now(): Instant = Instant.parse("2026-01-02T03:04:05Z")
        override fun timeZone(): TimeZone = TimeZone.UTC
    }

    private object NoopLogSink : RuntimeLogSink {
        override fun info(tag: String, msg: String) {}
        override fun warn(tag: String, msg: String, throwable: Throwable?) {}
        override fun error(tag: String, msg: String, throwable: Throwable?) {}
    }

    /** Pops ONE script per generation call, so multi-step turns are deterministic. */
    private class ScriptedProvider(
        scripts: List<List<MessageChunk>>,
    ) : Provider<ProviderSetting> {
        private val remaining = ArrayDeque(scripts)

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

    /** Satisfies the ProviderManager constructor; never dispatched on this code path. */
    private class UnusedProvider<T : ProviderSetting> : Provider<T> {
        override suspend fun listModels(providerSetting: T): List<Model> = error("unused")
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = error("unused")

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = error("unused")
    }

    private inner class FixedResolver(private val impl: Provider<ProviderSetting>) : ModelProviderResolver {
        override fun findModel(modelId: Uuid, turn: TurnConfig): Model? = model
        override fun findProvider(model: Model, turn: TurnConfig): ProviderSetting? = providerSetting
        override fun provider(setting: ProviderSetting): Provider<*> = impl
    }

    private val noopMemoryWriter = object : MemoryWriter {
        override suspend fun add(scope: MemoryScope, content: String): AssistantMemory = AssistantMemory(0, content)
        override suspend fun update(id: Int, content: String): AssistantMemory = AssistantMemory(id, content)
        override suspend fun delete(id: Int) {}
    }

    private val emptyReader = object : ConversationReader {
        override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> = emptyList()
    }

    private class CannedHookExecutor(private val response: String) : HookExecutor {
        override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String = response
    }

    private fun handler(scripted: ScriptedProvider, executor: HookExecutor): GenerationHandler = GenerationHandler(
        context = ContextWrapper(null),
        providerManager = ProviderManager(
            client = OkHttpClient(),
            context = ContextWrapper(null),
            providers = ProviderInstances(
                openAI = UnusedProvider(),
                google = UnusedProvider(),
                claude = UnusedProvider(),
            ),
        ),
        json = json,
        memoryWriter = noopMemoryWriter,
        conversationReader = emptyReader,
        modelProviderResolver = FixedResolver(scripted),
        clock = FixedRuntimeClock(),
        logSink = NoopLogSink,
        aiLoggingManager = AILoggingManager(),
        hookDispatcher = HookDispatcher(
            executors = mapOf(HookHandler.Llm::class to executor),
            logSink = NoopLogSink,
        ),
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

    @Test
    fun `trusted assistant deny hook blocks the tool through the production composition`() {
        val executor = CannedHookExecutor(
            """{"hookEventName":"PreToolUse","decision":"deny","reason":"blocked by hook policy"}"""
        )
        var executeCalls = 0
        val toolDef = Tool(name = "search", description = "", execute = {
            executeCalls++
            listOf(UIMessagePart.Text("result"))
        })
        // Step 1 emits the tool call; the denied result loops back for step 2's plain-text close.
        val scripted = ScriptedProvider(
            listOf(
                listOf(chunk(UIMessagePart.Tool(toolCallId = "call_1", toolName = "search", input = """{"q":"x"}"""))),
                listOf(chunk(UIMessagePart.Text("done"))),
            )
        )
        val assistant = Assistant(
            hooks = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(HookHandler.Llm(prompt = "gate"))),
                    ),
                ),
                trusted = true,
            ),
        )

        val messages = runBlocking {
            handler(scripted, executor).generateText(
                settings = Settings(),
                model = model,
                messages = listOf(
                    UIMessage.user("hello"),
                    UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
                ),
                assistant = assistant,
                tools = listOf(toolDef),
            ).toList().filterIsInstance<GenerationChunk.Messages>().last().messages
        }

        val tool = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolApprovalState.Denied("blocked by hook policy"), tool.approvalState)
        assertEquals("tool body must never run on deny", 0, executeCalls)
        assertTrue("hook denial must carry the provenance marker", isDeniedByHook(tool.metadata))
    }
}
