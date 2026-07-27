package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Moonshot (api.moonshot.cn) thinking.keep handling:
 * - K2.6 kept-thinking via thinking.keep = "all" (#1586)
 */
class ChatCompletionsAPIMoonshotTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private buildChatCompletionRequest via reflection
    private fun buildRequest(
        modelId: String,
        reasoningLevel: ReasoningLevel,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val model = Model(
            modelId = modelId,
            abilities = listOf(ModelAbility.REASONING)
        )
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = reasoningLevel,
        )
        val providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1")
        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            providerSetting,
            true
        ) as JsonObject
    }

    // #1586: K2.6 思考开启时发送 thinking.keep = "all"（保留式思考）
    @Test
    fun `k2_6 sends thinking keep all when reasoning enabled`() {
        val body = buildRequest("kimi-k2.6", ReasoningLevel.HIGH)
        val thinking = body["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertEquals("all", thinking?.get("keep")?.jsonPrimitive?.content)
    }

    // #1586: K2.6 关闭思考时不发送 keep（文档推荐 keep 与 enabled 搭配）
    @Test
    fun `k2_6 omits keep when reasoning disabled`() {
        val body = buildRequest("kimi-k2.6", ReasoningLevel.OFF)
        val thinking = body["thinking"]?.jsonObject
        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertFalse(thinking?.containsKey("keep") == true)
    }

    // #1586: K2.5 不支持 keep 参数，即使思考开启也不发送
    @Test
    fun `k2_5 never sends keep`() {
        val body = buildRequest("kimi-k2.5", ReasoningLevel.HIGH)
        val thinking = body["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertFalse(thinking?.containsKey("keep") == true)
    }
}
