package me.rerere.rikkahub.data.ai.task

import android.content.ContextWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
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
import me.rerere.ai.runtime.contract.TaskBudgetClock
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.ChatMessageTransformers
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
import org.junit.Test
import sun.misc.Unsafe
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TaskCoordinatorBridgeTest {

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            modules(
                module {
                    single<FilesManager> { fakeFilesManager() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    private val model = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())
    private val settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
    )

    private val noopMemoryWriter = object : MemoryWriter {
        override suspend fun add(scope: MemoryScope, content: String): AssistantMemory =
            AssistantMemory(0, content)

        override suspend fun update(id: Int, content: String): AssistantMemory =
            AssistantMemory(id, content)

        override suspend fun delete(id: Int) {}
    }

    private val emptyReader = object : ConversationReader {
        override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> = emptyList()
    }

    private fun fakeFilesManager(): FilesManager {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        return unsafe.allocateInstance(FilesManager::class.java) as FilesManager
    }

    private class FixedRuntimeClock : RuntimeClock {
        override fun now(): Instant = Instant.parse("2026-01-02T03:04:05Z")
        override fun timeZone(): TimeZone = TimeZone.UTC
    }

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
        ): Flow<MessageChunk> = flow {
            val script = remaining.removeFirst()
            script.forEach { emit(it) }
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow {}

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("unused")
    }

    private class UnusedProvider<T : ProviderSetting> : Provider<T> {
        override suspend fun listModels(providerSetting: T): List<Model> = error("unused")
        override suspend fun generateText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk =
            error("unused")
        override suspend fun streamText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> =
            error("unused")
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> =
            error("unused")
    }

    private class FixedResolver(
        private val model: Model,
        private val providerSetting: ProviderSetting,
        private val provider: Provider<ProviderSetting>,
    ) : ModelProviderResolver {
        override fun findModel(modelId: Uuid, turn: TurnConfig): Model? = model
        override fun findProvider(model: Model, turn: TurnConfig): ProviderSetting? = providerSetting
        override fun provider(setting: ProviderSetting): Provider<*> = provider
    }

    private object NoopLogSink : RuntimeLogSink {
        override fun info(tag: String, msg: String) {}
        override fun warn(tag: String, msg: String, throwable: Throwable?) {}
        override fun error(tag: String, msg: String, throwable: Throwable?) {}
    }

    private class NoopTaskBudgetClock : TaskBudgetClock {
        override fun monotonicNow(): Duration = Duration.ZERO
    }

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "test",
        model = "fake-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun fakeTemplateTransformer(): TemplateTransformer {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        return unsafe.allocateInstance(TemplateTransformer::class.java) as TemplateTransformer
    }

    private fun coordinator(): TaskCoordinator {
        val scripted = ScriptedProvider(
            listOf(
                listOf(
                    chunk(UIMessagePart.Text("<think>hidden</think>foo")),
                ),
            ),
        )
        val handler = GenerationHandler(
            context = ContextWrapper(null),
            providerManager = ProviderManager(
                client = OkHttpClient(),
                context = ContextWrapper(null),
                providers = ProviderInstances(
                    openAI = UnusedProvider(),
                    google = UnusedProvider(),
                    claude = UnusedProvider(),
                    chatGPT = UnusedProvider(),
                ),
            ),
            json = Json { encodeDefaults = true; explicitNulls = false },
            memoryWriter = noopMemoryWriter,
            conversationReader = emptyReader,
            modelProviderResolver = FixedResolver(
                model = model,
                providerSetting = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake"),
                provider = scripted,
            ),
            clock = FixedRuntimeClock(),
            logSink = NoopLogSink,
            aiLoggingManager = AILoggingManager(),
            hookDispatcher = HookDispatcher(
                executors = emptyMap(),
                logSink = NoopLogSink,
            ),
        )
        val transformers = ChatMessageTransformers(fakeTemplateTransformer()).also {
            val inputField = ChatMessageTransformers::class.java.getDeclaredField("input")
            inputField.isAccessible = true
            inputField.set(it, emptyList<InputMessageTransformer>())
        }

        return TaskCoordinator(
            generationHandler = handler,
            store = NoopTaskRunStore,
            clock = NoopTaskBudgetClock(),
            transformers = transformers,
        )
    }

    @Test
    fun `DI bridge runs subagent output through chat transformers`() = runBlocking {
        val sub = Assistant(
            name = "Sub",
            chatModelId = model.id,
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "strip-to-bar",
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )

        val result = coordinator().run(
            sub = sub,
            prompt = "anything",
            parentModelId = null,
            settings = settings,
        )

        assertEquals("bar", result.text)
    }
}
