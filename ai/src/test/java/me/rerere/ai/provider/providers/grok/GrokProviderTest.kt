package me.rerere.ai.provider.providers.grok

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.supportsQuota
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class GrokProviderTest {
    private val api = ResponseAPI(OkHttpClient())

    private fun grokBody(model: Model, level: ReasoningLevel = ReasoningLevel.AUTO): JsonObject = api.buildRequestBody(
        capabilities = GROK_CAPABILITIES,
        messages = listOf(UIMessage.system("Be brief."), UIMessage.user("hello")),
        params = TextGenerationParams(model = model, reasoningLevel = level),
        stream = true,
    )

    private fun json(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `system prompt moves into input as a system message`() {
        val body = grokBody(Model(modelId = "grok-4.3"))

        assertFalse("instructions" in body)
        val first = body["input"]!!.jsonArray.first().jsonObject
        assertEquals("system", first["role"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", first["content"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", body["include"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `session id is sent as prompt cache key`() {
        val body = api.buildRequestBody(
            capabilities = GROK_CAPABILITIES,
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "grok-4.3"), sessionId = "conv-1"),
            stream = true,
        )

        assertEquals("conv-1", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning effort is limited to what xai accepts`() {
        val reasoningModel = Model(modelId = "grok-4.3", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))

        fun effort(level: ReasoningLevel, model: Model = reasoningModel) =
            grokBody(model, level)["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content

        assertEquals("none", effort(ReasoningLevel.OFF))
        assertEquals("medium", effort(ReasoningLevel.MEDIUM))
        assertEquals("high", effort(ReasoningLevel.XHIGH))
        assertEquals("high", effort(ReasoningLevel.MAX))
        // 只有 grok-4.6 接受 xhigh
        val grok46 = reasoningModel.copy(modelId = "grok-4.6")
        assertEquals("xhigh", effort(ReasoningLevel.XHIGH, grok46))
        assertEquals("xhigh", effort(ReasoningLevel.MAX, grok46))
        // grok-4.20 带任何档位都会报错
        assertNull(effort(ReasoningLevel.HIGH, reasoningModel.copy(modelId = "grok-4.20-0309-reasoning")))
        assertNull(effort(ReasoningLevel.OFF, reasoningModel.copy(modelId = "grok-4.20-non-reasoning")))
        // 不指定档位时不带 reasoning，也不带 summary
        assertNull(grokBody(reasoningModel, ReasoningLevel.AUTO)["reasoning"])
        // 不接受档位的模型不带 reasoning，但仍然请求加密推理内容
        val plain = grokBody(Model(modelId = "grok-build"), ReasoningLevel.HIGH)
        assertNull(plain["reasoning"])
        assertTrue("include" in plain)
    }

    @Test
    fun `model catalogs are merged with grok cli metadata first`() {
        val models = parseGrokModels(
            cliCatalog = json(
                """{"data":[
                    {"model":"grok-4.3","name":"Grok 4.3","reasoningEfforts":[{"value":"low"},{"value":"high"}],"contextWindow":1000000},
                    {"modelId":"grok-build","_meta":{"totalContextTokens":512000}},
                    {"model":"grok-secret","hidden":true},
                    {"model":"grok-imagine-image"}
                ]}"""
            ),
            apiCatalog = json(
                """{"data":[{"id":"grok-4.3"},{"id":"grok-4.20-0309-non-reasoning"},{"id":"grok-tts"}]}"""
            ),
        )

        assertEquals(listOf("grok-4.3", "grok-build", "grok-4.20-0309-non-reasoning"), models.map { it.modelId })
        val grok43 = models[0]
        assertEquals("Grok 4.3", grok43.displayName)
        assertTrue(ModelAbility.REASONING in grok43.abilities)
        assertEquals(1_000_000, grok43.contextLength)
        assertFalse(ModelAbility.REASONING in models[1].abilities)
        assertEquals(512_000, models[1].contextLength)
    }

    @Test
    fun `weekly credits map to a weekly window`() {
        val usage = parseGrokWeeklyUsage(
            json(
                """{"config":{"creditUsagePercent":"37.5","currentPeriod":
                    {"start":"2026-10-13T00:00:00Z","end":"2026-10-20T00:00:00Z","type":"BILLING_PERIOD_TYPE_WEEKLY"}}}"""
            ),
            now = Instant.parse("2026-10-19T00:00:00Z"),
        )

        assertEquals(
            ProviderQuota.UsageLimits(
                windows = listOf(UsageWindow(37.5, 10080, Instant.parse("2026-10-20T00:00:00Z"))),
                limitReached = false,
            ),
            usage,
        )
    }

    @Test
    fun `weekly credits without usage count as zero only while the period is active`() {
        val body = json(
            """{"config":{"currentPeriod":{"start":"2026-10-13T00:00:00Z","end":"2026-10-20T00:00:00Z","type":"WEEKLY"}}}"""
        )

        assertEquals(
            0.0,
            parseGrokWeeklyUsage(body, now = Instant.parse("2026-10-14T00:00:00Z"))!!.windows.single().usedPercent,
            0.0
        )
        assertNull(parseGrokWeeklyUsage(body, now = Instant.parse("2026-10-21T00:00:00Z")))
        assertNull(parseGrokWeeklyUsage(json("""{"config":{"monthlyLimit":{"val":100}}}""")))
    }

    @Test
    fun `monthly included quota uses the billing period`() {
        val usage = parseGrokMonthlyUsage(
            json(
                """{"config":{"billingPeriodStart":"2026-10-01T00:00:00Z","billingPeriodEnd":"2026-10-31T00:00:00Z",
                    "monthlyLimit":{"val":"2000"},"used":{"val":2000}}}"""
            )
        )!!

        assertEquals(UsageWindow(100.0, 30 * 24 * 60, Instant.parse("2026-10-31T00:00:00Z")), usage.windows.single())
        assertTrue(usage.limitReached)
        assertNull(
            parseGrokMonthlyUsage(
                json(
                    """{"config":{"billingPeriodStart":"2026-10-01T00:00:00Z","billingPeriodEnd":"2026-10-31T00:00:00Z",
                        "monthlyLimit":{"val":0},"used":{"val":0}}}"""
                )
            )
        )
    }

    @Test
    fun `grok setting round trips with its credential`() {
        val setting = ProviderSetting.Grok(
            credential = OAuthCredential(accessToken = "a", refreshToken = "r", accountId = "sub")
        )

        val encoded = json.encodeToString(ProviderSetting.serializer(), setting)
        val decoded = json.decodeFromString(ProviderSetting.serializer(), encoded)

        assertTrue(encoded.contains("\"type\":\"grok\""))
        assertEquals(setting, decoded)
        assertTrue(decoded.supportsQuota)
    }
}
