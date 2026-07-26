package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
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
 * Unit tests for ChatCompletionsAPI Moonshot-dialect handling:
 * - Moonshot per-model reasoning params (#1573)
 * - Moonshot K2.5+ sampling param filtering (#1574)
 */
class ChatCompletionsAPIDialectTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private buildChatCompletionRequest via reflection
    private fun buildRequest(
        baseUrl: String,
        modelId: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
        temperature: Float? = null,
        topP: Float? = null,
        reasoning: Boolean = true,
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
            abilities = if (reasoning) listOf(ModelAbility.REASONING) else emptyList()
        )
        val params = TextGenerationParams(
            model = model,
            temperature = temperature,
            topP = topP,
            reasoningLevel = reasoningLevel,
        )
        val providerSetting = ProviderSetting.OpenAI(baseUrl = baseUrl)
        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            providerSetting,
            true
        ) as JsonObject
    }

    // #1573: kimi-k3 不使用 thinking 参数，顶层 reasoning_effort 仅接受 low/high/max
    @Test
    fun `k3 uses reasoning_effort with mapped levels`() {
        val body = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k3",
            reasoningLevel = ReasoningLevel.HIGH
        )
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("thinking"))

        assertEquals(
            "max",
            buildRequest("https://api.moonshot.cn/v1", "kimi-k3", reasoningLevel = ReasoningLevel.XHIGH)
                ["reasoning_effort"]?.jsonPrimitive?.content
        )
        assertEquals(
            "high",
            buildRequest("https://api.moonshot.cn/v1", "kimi-k3", reasoningLevel = ReasoningLevel.MEDIUM)
                ["reasoning_effort"]?.jsonPrimitive?.content
        )
        assertEquals(
            "low",
            buildRequest("https://api.moonshot.cn/v1", "kimi-k3", reasoningLevel = ReasoningLevel.LOW)
                ["reasoning_effort"]?.jsonPrimitive?.content
        )
        // K3 始终推理，OFF 映射为最低档 low
        assertEquals(
            "low",
            buildRequest("https://api.moonshot.cn/v1", "kimi-k3", reasoningLevel = ReasoningLevel.OFF)
                ["reasoning_effort"]?.jsonPrimitive?.content
        )
    }

    // #1573: AUTO 时什么都不发，让服务端默认值生效
    @Test
    fun `k3 auto sends no reasoning params`() {
        val body = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k3",
            reasoningLevel = ReasoningLevel.AUTO
        )
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("thinking"))
    }

    // #1573: 裸 id "k3" 与国际站 api.moonshot.ai 走同一 K3 方言
    @Test
    fun `bare k3 id and international host share the k3 dialect`() {
        val bare = buildRequest(
            "https://api.moonshot.cn/v1", "k3",
            reasoningLevel = ReasoningLevel.HIGH
        )
        assertEquals("high", bare["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(bare.containsKey("thinking"))

        val intl = buildRequest(
            "https://api.moonshot.ai/v1", "kimi-k3",
            reasoningLevel = ReasoningLevel.XHIGH
        )
        assertEquals("max", intl["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(intl.containsKey("thinking"))
    }

    // #1573: kimi-k2.7-code 始终开启思考，关闭推理时也不能发送 thinking disabled（会 400）
    @Test
    fun `k2_7-code sends no thinking params even when off`() {
        val off = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k2.7-code",
            reasoningLevel = ReasoningLevel.OFF
        )
        assertFalse(off.containsKey("thinking"))
        assertFalse(off.containsKey("reasoning_effort"))

        val high = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k2.7-code-highspeed",
            reasoningLevel = ReasoningLevel.HIGH
        )
        assertFalse(high.containsKey("thinking"))
        assertFalse(high.containsKey("reasoning_effort"))
    }

    // #1573: K2.5/K2.6 等仍使用 thinking enabled/disabled 方言
    @Test
    fun `k2_5 keeps the thinking enabled_disabled dialect`() {
        val off = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k2.5",
            reasoningLevel = ReasoningLevel.OFF
        )
        assertEquals(
            "disabled",
            off["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        )

        val on = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k2.5",
            reasoningLevel = ReasoningLevel.HIGH
        )
        assertEquals(
            "enabled",
            on["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
    }

    // #1574: 月之暗面 K2.5 及以上模型不发送 temperature/top_p（固定值，显式传入会 400）
    @Test
    fun `sampling params are dropped for moonshot restricted models`() {
        val k3 = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k3",
            temperature = 0.7f, topP = 0.9f, reasoning = false
        )
        assertFalse(k3.containsKey("temperature"))
        assertFalse(k3.containsKey("top_p"))

        val k25 = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k2.5",
            temperature = 0.7f, reasoning = false
        )
        assertFalse(k25.containsKey("temperature"))

        val intl = buildRequest(
            "https://api.moonshot.ai/v1", "kimi-k2.6",
            temperature = 0.7f, reasoning = false
        )
        assertFalse(intl.containsKey("temperature"))
    }

    // #1574: 受限范围之外的模型与 host 照常发送采样参数
    @Test
    fun `sampling params are kept for unrestricted models and hosts`() {
        // 早期 K2 不受限
        val k2 = buildRequest(
            "https://api.moonshot.cn/v1", "kimi-k2-0905-preview",
            temperature = 0.7f, topP = 0.9f, reasoning = false
        )
        assertEquals(0.7f, k2["temperature"]?.jsonPrimitive?.floatOrNull)
        assertEquals(0.9f, k2["top_p"]?.jsonPrimitive?.floatOrNull)

        // 非 moonshot host 不受限
        val ds = buildRequest(
            "https://api.deepseek.com/v1", "deepseek-chat",
            temperature = 0.7f, reasoning = false
        )
        assertEquals(0.7f, ds["temperature"]?.jsonPrimitive?.floatOrNull)
    }

    // #1573: Kimi Code 网关（api.kimi.com/coding）与开放平台共用同一套月之暗面方言
    @Test
    fun `kimi code gateway uses the same moonshot dialect`() {
        // k3 / k3-256k：顶层 reasoning_effort 原生档位
        val k3 = buildRequest(
            "https://api.kimi.com/coding/v1", "k3",
            reasoningLevel = ReasoningLevel.HIGH
        )
        assertEquals("high", k3["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(k3.containsKey("thinking"))

        val k3256k = buildRequest(
            "https://api.kimi.com/coding/v1", "k3-256k",
            reasoningLevel = ReasoningLevel.XHIGH
        )
        assertEquals("max", k3256k["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(k3256k.containsKey("thinking"))

        // kimi-for-coding：K2.7 Code 方言，任何档位都不传思考参数
        val coding = buildRequest(
            "https://api.kimi.com/coding/v1", "kimi-for-coding",
            reasoningLevel = ReasoningLevel.OFF
        )
        assertFalse(coding.containsKey("thinking"))
        assertFalse(coding.containsKey("reasoning_effort"))
    }

    // #1574: Kimi Code 网关同样过滤 K2.5+ 模型的采样参数
    @Test
    fun `sampling params are dropped on the kimi code gateway`() {
        val coding = buildRequest(
            "https://api.kimi.com/coding/v1", "kimi-for-coding",
            temperature = 0.7f, topP = 0.9f, reasoning = false
        )
        assertFalse(coding.containsKey("temperature"))
        assertFalse(coding.containsKey("top_p"))

        val k3 = buildRequest(
            "https://api.kimi.com/coding/v1", "k3",
            temperature = 0.7f, reasoning = false
        )
        assertFalse(k3.containsKey("temperature"))
    }
}
