package me.rerere.ai.provider

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责根据 ProviderSetting 调度对应的 Provider 实例
 *
 * @param client 共享 OkHttpClient，用于非流式调用（generateText/listModels），保留较长的 readTimeout
 * @param streamClient 专用流式 OkHttpClient，用于 SSE EventSource，带有更短的 readTimeout 以便快速失败。
 *   默认回退到 client 以保持向后兼容（现有调用方/测试无需改动即可编译）。
 * @param providers 三个 sealed-typed Provider 实例，默认由 [ProviderInstances.default] 构造；
 *   测试可注入 fake 实例以断言调度行为。
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    streamClient: OkHttpClient = client,
    providers: ProviderInstances = ProviderInstances.default(client, context, streamClient),
) {
    private val openAI = providers.openAI
    private val google = providers.google
    private val claude = providers.claude
    private val chatGPT = providers.chatGPT

    /**
     * 按名称获取Provider实例（只读）
     *
     * @param name Provider名称（openai/google/claude）
     * @return 对应的Provider实例
     * @throws IllegalArgumentException 名称未知时抛出
     */
    fun getProvider(name: String): Provider<*> = when (name) {
        "openai" -> openAI
        "google" -> google
        "claude" -> claude
        "chatgpt" -> chatGPT
        else -> throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return 对应的Provider实例
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            // OpenAI now carries its transport mode: ChatGPT mode routes to the isolated Codex wire
            // ([chatGPT], which is itself a Provider<OpenAI> reading the accessToken), every other mode
            // to the standard OpenAI-compatible wire. This is the OpenAI analog of the Google provider's
            // internal Vertex/Gagy branching.
            is ProviderSetting.OpenAI -> when (setting.mode) {
                OpenAIMode.ChatGPT -> chatGPT
                // Standard and Azure both run on the OpenAI wire; OpenAIProvider branches Azure's
                // deployment URL + api-key auth internally.
                OpenAIMode.Standard, OpenAIMode.Azure -> openAI
            }

            is ProviderSetting.Google -> google
            is ProviderSetting.Claude -> claude
        } as Provider<T>
    }
}
