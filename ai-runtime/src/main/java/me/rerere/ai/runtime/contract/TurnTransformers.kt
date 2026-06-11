package me.rerere.ai.runtime.contract

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage

/**
 * Neutral functional seam for the message-transformer pipeline (issue #243 §C step 9).
 *
 * The transformer pipeline itself (`Transformer.kt`, OCR/template/regex/prompt-injection) is still
 * app-coupled to Android `Context` / `Settings` / `Assistant` — moving it behind a neutral
 * `GenerationContext` is a SEPARATE slice (§C step 7, not landed). To keep THIS slice a pure
 * behavior-preserving code-move, the chat-turn loop calls the pipeline only through this seam: the
 * app binds it over its existing `.transforms()` / `.visualTransforms()` / `.onGenerationFinish()`
 * extension functions (which still hold the Android context on the app side), and the runtime never
 * imports the app transformers.
 *
 * All four operations take/return [UIMessage] lists — a `:ai` type, so the seam carries no app
 * dependency. The order/semantics mirror the original `generateText`/`generateInternal` call sites
 * 1:1: [transformInput] runs over the assembled internal messages before sending; [transformOutput],
 * [visualTransform] and [onGenerationFinish] run over the accumulated reply messages.
 */
interface TurnMessageTransforms {
    /** Input pipeline — the original `internalMessages.transforms(inputTransformers, …)`. */
    suspend fun transformInput(messages: List<UIMessage>): List<UIMessage>

    /** Output pipeline — the original `messages.transforms(outputTransformers, …)`. */
    suspend fun transformOutput(messages: List<UIMessage>): List<UIMessage>

    /** Visual pipeline — the original `messages.visualTransforms(outputTransformers, …)`. */
    suspend fun visualTransform(messages: List<UIMessage>): List<UIMessage>

    /** Finish pipeline — the original `messages.onGenerationFinish(outputTransformers, …)`. */
    suspend fun onGenerationFinish(messages: List<UIMessage>): List<UIMessage>
}

/**
 * Neutral seam for the AI-request generation log (issue #243 §C step 9).
 *
 * The app's `AILoggingManager` is coupled to `AILogging` → `ProviderSetting`; rather than move the
 * logging manager (out of scope for this slice), the runtime records each generation request through
 * this port. The app binds it over `AILoggingManager.addLog(AILogging.Generation(...))`. All argument
 * types ([UIMessage], [TextGenerationParams], [ProviderSetting]) live in `:ai`, so the seam is
 * neutral.
 */
fun interface RuntimeGenerationLog {
    fun onGeneration(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        provider: ProviderSetting,
        stream: Boolean,
    )
}
