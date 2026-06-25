package me.rerere.rikkahub.data.ai.goal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.service.backgroundTextGenerationParams
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the `/goal` judge as a single-shot background call against the supplied chat [Model] and maps
 * the response through [parseGoalVerdict] (#364 root-cause fix). Unlike the old Stop-hook judge it
 * does NOT resolve the settings fast model — the caller passes the model the just-finished turn ran
 * on, so the judge is as available as the turn itself. Any provider failure, timeout, or missing
 * provider degrades to [GoalVerdict.Inconclusive] (pause, keep the goal armed) — never a false [Met].
 *
 * [callTimeout] bounds the call two ways (mirroring [me.rerere.rikkahub.data.ai.hooks.LlmHookExecutor]):
 * `withTimeoutOrNull` cancels while suspended waiting for headers, and the same bound is pushed down
 * as [me.rerere.ai.core.TextGenerationParams.callTimeoutMillis] for OkHttp's per-call body-read
 * timeout. A real (external) cancellation still propagates untouched.
 */
internal class LlmGoalEvaluator(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val callTimeout: Duration = 30.seconds,
) : GoalEvaluator {

    override suspend fun judge(condition: String, lastAssistantText: String?, model: Model): GoalVerdict {
        val current = settingsStore.settingsFlow.value
        val provider = model.findProvider(current.providers) ?: return GoalVerdict.Inconclusive
        val providerHandler = providerManager.getProviderByType(provider)
        val raw = try {
            withTimeoutOrNull(callTimeout) {
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(goalJudgePrompt(condition, lastAssistantText))),
                    params = backgroundTextGenerationParams(model)
                        .copy(callTimeoutMillis = callTimeout.inWholeMilliseconds),
                )
            }?.choices?.firstOrNull()?.message?.toText().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A judge failure must NOT look like "met"; pause the goal instead of clearing it.
            return GoalVerdict.Inconclusive
        }
        return parseGoalVerdict(raw)
    }
}
