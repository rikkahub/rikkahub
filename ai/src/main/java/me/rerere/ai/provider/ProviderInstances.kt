package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.ChatGPTProvider
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * 三个 sealed-typed Provider 实例的容器。
 *
 * 高层调度 ([ProviderManager.getProviderByType]) 依赖此抽象而非具体 Provider；
 * 默认实例由 [default] 在组合根注入，测试可注入 fake 实例。
 */
data class ProviderInstances(
    val openAI: Provider<ProviderSetting.OpenAI>,
    val google: Provider<ProviderSetting.Google>,
    val claude: Provider<ProviderSetting.Claude>,
    val chatGPT: Provider<ProviderSetting.ChatGPT>,
) {
    companion object {
        fun default(
            client: OkHttpClient,
            context: Context,
            streamClient: OkHttpClient,
        ) = ProviderInstances(
            openAI = OpenAIProvider(client, context, streamClient),
            google = GoogleProvider(client, context, streamClient),
            claude = ClaudeProvider(client, context, streamClient),
            chatGPT = ChatGPTProvider(client, context, streamClient),
        )
    }
}
