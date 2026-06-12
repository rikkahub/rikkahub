package me.rerere.rikkahub.data.ai.hooks

import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.service.backgroundTextGenerationParams
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the current [Settings] snapshot for hook execution. A port instead of a direct
 * `SettingsStore` dependency because the store is Android/DataStore-bound — this keeps the
 * executor (and its Koin module) resolvable in plain JVM tests.
 */
fun interface HookSettingsReader {
    fun current(): Settings
}

/**
 * Runs an [HookHandler.Llm] hook as a single-shot background call against the handler's model
 * (`model = null` means the settings fast model; a pinned model that is missing FAILS rather
 * than falling back) and returns the raw response text for `parseHookOutput` (#200 v1,
 * spec §LlmHookExecutor).
 *
 * Timeout contract (H1): [callTimeout] bounds every hook call independently of the shared
 * OkHttp client's 10-minute read-timeout ceiling, via TWO complementary mechanisms.
 * `withTimeoutOrNull` cancels while the call is suspended waiting for headers (Call.await()
 * cancels the in-flight call on coroutine cancellation), but it CANNOT interrupt the provider's
 * blocking body read once headers arrived — so the same bound is also pushed down as
 * [TextGenerationParams.callTimeoutMillis], OkHttp's per-call timeout spanning the entire call
 * including body reads. Either bound is rethrown as a plain failure — never a
 * CancellationException — so the dispatcher maps it through the handler's `failClosed` policy
 * instead of cancelling the whole dispatch; real (external) cancellation still propagates
 * untouched.
 */
class LlmHookExecutor(
    private val settings: HookSettingsReader,
    private val providerManager: ProviderManager,
    private val callTimeout: Duration = 15.seconds,
) : HookExecutor {

    override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String {
        check(handler is HookHandler.Llm) {
            "LlmHookExecutor cannot run ${handler::class.simpleName} handlers"
        }
        val current = settings.current()
        // HookConfig documents `model = null` as the ONLY fast-model case. A pinned model that
        // is missing (deleted/unavailable) must surface as a plain failure — the dispatcher
        // maps it through the handler's failClosed policy — never a silent swap onto the fast
        // model, which would change the enforcement model of failClosed security hooks.
        val model = if (handler.model == null) {
            current.findModelById(current.fastModelId)
                ?: error("hook fast model not found (fast model=${current.fastModelId})")
        } else {
            current.findModelById(handler.model)
                ?: error("pinned hook model not found (handler model=${handler.model}); refusing fast-model fallback")
        }
        val provider = model.findProvider(current.providers)
            ?: error("no provider configured for hook model ${model.modelId}")
        val providerHandler = providerManager.getProviderByType(provider)
        val result = withTimeoutOrNull(callTimeout) {
            providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(buildPrompt(event, handler, input))),
                params = backgroundTextGenerationParams(model)
                    .copy(callTimeoutMillis = callTimeout.inWholeMilliseconds),
            )
        } ?: error("hook llm call timed out after $callTimeout")
        return result.choices.firstOrNull()?.message?.toText().orEmpty()
    }

    private fun buildPrompt(event: HookEvent, handler: HookHandler.Llm, input: String): String = buildString {
        appendLine("You are evaluating the '${event.name}' lifecycle event of an agent loop.")
        appendLine(handler.prompt)
        appendLine()
        appendLine("Event input:")
        appendLine(input)
        appendLine()
        append("Respond with ONLY a JSON object of the shape ")
        append("{\"hookEventName\":\"${event.name}\",\"decision\":\"allow|ask|deny\",\"reason\":\"...\",")
        append("\"updatedInput\":null,\"additionalContext\":null,\"preventContinuation\":false}. ")
        append("Omit fields you do not need; \"decision\" defaults to allow.")
    }
}
