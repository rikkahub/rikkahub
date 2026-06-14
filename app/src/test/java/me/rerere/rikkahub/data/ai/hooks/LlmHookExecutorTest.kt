package me.rerere.rikkahub.data.ai.hooks

import android.content.ContextWrapper
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderInstances
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.runtime.contract.RuntimeLogSink
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookDecision
import me.rerere.ai.runtime.hooks.HookDispatchContext
import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.ai.runtime.hooks.StaticHookExecutor
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.di.hooksModule
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class LlmHookExecutorTest {

    private val hookModel = Model(modelId = "fast-model", displayName = "Fast")
    private val settings = Settings(
        providers = listOf(ProviderSetting.OpenAI(models = listOf(hookModel))),
        fastModelId = hookModel.id,
    )

    // T6 acceptance: a cancelled hook coroutine must cancel the underlying HTTP call (H1 chain:
    // executor -> provider -> Call.await() -> invokeOnCancellation { cancel() }).
    @Test
    fun `cancelled hook coroutine cancels the underlying call`() = runBlocking {
        val call = FakeCall()
        val executor = LlmHookExecutor(
            settings = { settings },
            providerManager = providerManager(FakeOpenAIProvider {
                call.await()
                error("unreachable: fake call never responds")
            }),
        )

        val job = launch {
            executor.execute(HookEvent.PreToolUse, llmHandler(), input = "{}")
        }
        while (call.enqueuedCallback == null) {
            yield()
        }
        job.cancelAndJoin()

        assertTrue("Call.cancel() must be invoked on hook coroutine cancellation", call.isCanceled())
    }

    // The per-hook callTimeout must bound the call on its own — the fake call hangs forever,
    // standing in for the shared client's 10-minute read-timeout ceiling. The timeout must
    // surface as a plain failure (failClosed policy decides the outcome), never as a
    // CancellationException that would cancel the whole dispatch.
    @Test
    fun `short per-hook call timeout is honored independent of the shared client ceiling`() = runBlocking {
        val call = FakeCall()
        val executor = LlmHookExecutor(
            settings = { settings },
            providerManager = providerManager(FakeOpenAIProvider {
                call.await()
                error("unreachable: fake call never responds")
            }),
            callTimeout = 200.milliseconds,
        )

        try {
            executor.execute(HookEvent.PreToolUse, llmHandler(), input = "{}")
            fail("expected the per-hook timeout to fail the call")
        } catch (e: CancellationException) {
            fail("hook timeout must not surface as CancellationException: $e")
        } catch (e: Exception) {
            assertTrue("failure should name the timeout, got: ${e.message}", e.message.orEmpty().contains("timed out"))
        }
        assertTrue("the timed-out in-flight call must be cancelled", call.isCanceled())
    }

    // Codex review mustFix #1: withTimeoutOrNull alone cannot interrupt the provider's BLOCKING
    // body read once headers arrived — only OkHttp's per-call timeout spans the whole call. The
    // executor must therefore push its timeout down to the HTTP layer via
    // TextGenerationParams.callTimeoutMillis.
    @Test
    fun `hook call carries callTimeoutMillis down to the provider params`() = runBlocking {
        var seen: TextGenerationParams? = null
        val executor = LlmHookExecutor(
            settings = { settings },
            providerManager = providerManager(FakeOpenAIProvider { params ->
                seen = params
                denyChunk("ok")
            }),
            callTimeout = 200.milliseconds,
        )

        executor.execute(HookEvent.PreToolUse, llmHandler(), input = "{}")

        assertEquals(200L, seen?.callTimeoutMillis)
    }

    // HookConfig documents `model = null` as the ONLY fast-model case. A pinned model that is
    // missing (deleted/unavailable) must fail through the dispatcher, never silently swap the
    // call onto the fast model — for failClosed security hooks that swap would change the
    // enforcement model.
    @Test
    fun `pinned hook model that is missing fails instead of falling back to the fast model`() = runBlocking {
        var providerCalled = false
        val executor = LlmHookExecutor(
            settings = { settings },
            providerManager = providerManager(FakeOpenAIProvider {
                providerCalled = true
                denyChunk("should never run")
            }),
        )
        val pinnedMissing = Model(modelId = "deleted-model", displayName = "Deleted")

        try {
            executor.execute(HookEvent.PreToolUse, llmHandler(model = pinnedMissing.id), input = "{}")
            fail("expected the missing pinned model to fail the hook call")
        } catch (e: IllegalStateException) {
            assertTrue(
                "failure should name the pinned model, got: ${e.message}",
                e.message.orEmpty().contains(pinnedMissing.id.toString()),
            )
        }
        assertFalse("the fast model must NOT be called in place of the pinned model", providerCalled)
    }

    @Test
    fun `failClosed hook pinned to a missing model denies instead of running under the fast model`() = runBlocking {
        val pinnedMissing = Model(modelId = "deleted-model", displayName = "Deleted")
        // The fast model exists and would answer "allow" — exactly the silent-downgrade hazard.
        val dispatcher = dispatcherWith(FakeOpenAIProvider { allowChunk() })

        val result = dispatcher.dispatch(
            event = HookEvent.PreToolUse,
            input = "{}",
            ctx = HookDispatchContext(
                config = trustedLlmConfig(failClosed = true, model = pinnedMissing.id),
                toolName = "search",
            ),
        )

        assertTrue(
            "missing pinned model on a failClosed hook must deny, got ${result.decision}",
            result.decision is HookDecision.Deny,
        )
    }

    @Test
    fun `failClosed executor error aggregates as Deny`() = runBlocking {
        val dispatcher = dispatcherWith(FakeOpenAIProvider { throw IOException("provider down") })

        val result = dispatcher.dispatch(
            event = HookEvent.PreToolUse,
            input = "{}",
            ctx = HookDispatchContext(config = trustedLlmConfig(failClosed = true), toolName = "search"),
        )

        assertTrue("failClosed error must deny, got ${result.decision}", result.decision is HookDecision.Deny)
    }

    @Test
    fun `fail-open executor error degrades to Allow`() = runBlocking {
        val dispatcher = dispatcherWith(FakeOpenAIProvider { throw IOException("provider down") })

        val result = dispatcher.dispatch(
            event = HookEvent.PreToolUse,
            input = "{}",
            ctx = HookDispatchContext(config = trustedLlmConfig(failClosed = false), toolName = "search"),
        )

        assertEquals(HookDecision.Allow, result.decision)
    }

    // T6 acceptance: the Koin composition root resolves HookDispatcher with the
    // Llm -> LlmHookExecutor binding. Only platform leaves (settings reader, provider manager,
    // log sink) are substituted; the module's own wiring is the real one under test.
    @Test
    fun `koin module resolves HookDispatcher with Llm bound to LlmHookExecutor`() {
        val koin = koinApplication {
            modules(
                hooksModule,
                module {
                    single<HookSettingsReader> { HookSettingsReader { settings } }
                    single { providerManager(FakeOpenAIProvider { denyChunk("blocked by hook") }) }
                    single<RuntimeLogSink> { NoopLogSink }
                },
            )
        }.koin

        val registry = koin.get<HookExecutorRegistry>()
        assertTrue(
            "registry must bind HookHandler.Llm to LlmHookExecutor",
            registry.executors[HookHandler.Llm::class] is LlmHookExecutor,
        )

        val dispatcher = koin.get<HookDispatcher>()
        val result = runBlocking {
            dispatcher.dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = trustedLlmConfig(failClosed = false), toolName = "search"),
            )
        }
        assertEquals(HookDecision.Deny("blocked by hook"), result.decision)
    }

    // ---- fixtures ----

    private fun llmHandler(failClosed: Boolean = false, model: Uuid? = null) =
        HookHandler.Llm(prompt = "gate this tool", model = model, failClosed = failClosed)

    private fun trustedLlmConfig(failClosed: Boolean, model: Uuid? = null) = HookConfig(
        hooks = mapOf(
            HookEvent.PreToolUse to listOf(
                HookMatcher(matcher = null, handlers = listOf(llmHandler(failClosed, model))),
            ),
        ),
        trusted = true,
    )

    private fun dispatcherWith(openAI: Provider<ProviderSetting.OpenAI>): HookDispatcher {
        val executor = LlmHookExecutor(
            settings = { settings },
            providerManager = providerManager(openAI),
        )
        return HookDispatcher(
            executors = HookExecutorRegistry(llm = executor, static = StaticHookExecutor()).executors,
            logSink = NoopLogSink,
        )
    }

    // ContextWrapper(null) is safe: the manager never touches the context when provider
    // instances are injected (same seam as ProviderManagerTest).
    private fun providerManager(openAI: Provider<ProviderSetting.OpenAI>) = ProviderManager(
        client = OkHttpClient(),
        context = ContextWrapper(null),
        providers = ProviderInstances(
            openAI = openAI,
            google = UnusedProvider(),
            claude = UnusedProvider(),
            chatGPT = UnusedProvider(),
        ),
    )

    private fun allowChunk() = MessageChunk(
        id = "hook-1",
        model = "fast-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = UIMessage.assistant(
                    """{"hookEventName":"PreToolUse","decision":"allow"}""",
                ),
                finishReason = "stop",
            ),
        ),
    )

    private fun denyChunk(reason: String) = MessageChunk(
        id = "hook-1",
        model = "fast-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = UIMessage.assistant(
                    """{"hookEventName":"PreToolUse","decision":"deny","reason":"$reason"}""",
                ),
                finishReason = "stop",
            ),
        ),
    )

    private object NoopLogSink : RuntimeLogSink {
        override fun info(tag: String, msg: String) {}
        override fun warn(tag: String, msg: String, throwable: Throwable?) {}
        override fun error(tag: String, msg: String, throwable: Throwable?) {}
    }

    private class FakeOpenAIProvider(
        private val onGenerateText: suspend (TextGenerationParams) -> MessageChunk,
    ) : Provider<ProviderSetting.OpenAI> {
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = onGenerateText(params)

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = emptyFlow()

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = emptyFlow()
    }

    private class UnusedProvider<T : ProviderSetting> : Provider<T> {
        override suspend fun listModels(providerSetting: T): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = emptyFlow()

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = emptyFlow()
    }

    private class FakeCall : Call {
        @Volatile
        var enqueuedCallback: Callback? = null

        @Volatile
        private var canceled = false

        private val request: Request = Request.Builder().url("http://localhost/").build()

        override fun request(): Request = request

        override fun execute(): okhttp3.Response = throw UnsupportedOperationException("fake")

        override fun enqueue(responseCallback: Callback) {
            enqueuedCallback = responseCallback
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = enqueuedCallback != null

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = this
    }
}
