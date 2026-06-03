package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeProviderOAuthSystemTest {
    // Must match the private constants in ClaudeProvider.kt — the OAuth gate
    // requires these two blocks to be the first two system blocks verbatim.
    private val billing =
        "x-anthropic-billing-header: cc_version=2.1.126.88c; cc_entrypoint=cli; cch=00000;"
    private val identity = "You are Claude Code, Anthropic's official CLI for Claude."

    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    private fun buildRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(provider, providerSetting, messages, params, stream) as JsonObject
    }

    private fun systemTextAt(request: JsonObject, index: Int): String =
        request["system"]!!.jsonArray[index].jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `OAuth custom system array must not drop the two fingerprint blocks`() {
        val providerSetting = ProviderSetting.Claude(
            authType = ClaudeAuthType.OAuth,
            oauthToken = "token"
        )
        val messages = listOf(
            UIMessage.system("caller system prompt"),
            UIMessage.user("hello")
        )
        // A user-provided custom body that overrides `system` with its own array.
        // mergeCustomBody replaces the array wholesale (it only deep-merges objects),
        // which previously dropped the OAuth fingerprints.
        val customSystem = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "user custom block")
            })
        }
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test"),
            customBody = listOf(CustomBody(key = "system", value = customSystem))
        )

        val request = buildRequest(providerSetting, messages, params)
        val system = request["system"]!!.jsonArray

        // The two fingerprint blocks must lead, in order.
        assertEquals(billing, systemTextAt(request, 0))
        assertEquals(identity, systemTextAt(request, 1))

        // The user's custom block must survive after the fingerprints.
        val texts = system.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(
            "custom system block must be preserved, got $texts",
            texts.contains("user custom block")
        )
    }

    @Test
    fun `OAuth without custom system keeps prompt-cache breakpoint on last fingerprint block`() {
        // OAuth + promptCaching=true + no caller system message. The original assembly
        // produces system=[FP_BILLING, FP_IDENTITY{cache_control}]. ensureOAuthFingerprints
        // must NOT run here (the custom body never overrode `system`), otherwise it rebuilds
        // the fingerprints via textSystemBlock() and silently drops the cache breakpoint.
        val providerSetting = ProviderSetting.Claude(
            authType = ClaudeAuthType.OAuth,
            oauthToken = "token",
            promptCaching = true
        )
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(model = Model(modelId = "claude-test"))

        val request = buildRequest(providerSetting, messages, params)
        val system = request["system"]!!.jsonArray

        assertEquals(billing, systemTextAt(request, 0))
        assertEquals(identity, systemTextAt(request, 1))

        // The last system block (FP_IDENTITY) must carry the cache breakpoint.
        val cacheControl = system.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ApiKey custom system array replaces system untouched`() {
        val providerSetting = ProviderSetting.Claude(authType = ClaudeAuthType.ApiKey)
        val messages = listOf(
            UIMessage.system("caller system prompt"),
            UIMessage.user("hello")
        )
        val customSystem = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "user custom block")
            })
        }
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test"),
            customBody = listOf(CustomBody(key = "system", value = customSystem))
        )

        val request = buildRequest(providerSetting, messages, params)
        val texts = request["system"]!!.jsonArray.map {
            it.jsonObject["text"]!!.jsonPrimitive.content
        }

        // ApiKey mode has no fingerprint invariant: the custom body wins verbatim.
        assertEquals(listOf("user custom block"), texts)
    }
}
