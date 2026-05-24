package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatCompletionsAPIPromptCacheTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun buildRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(api, messages, params, providerSetting, stream) as JsonObject
    }

    private fun openRouter(promptCaching: Boolean = true) = ProviderSetting.OpenAI(
        baseUrl = "https://openrouter.ai/api/v1",
        promptCaching = promptCaching
    )

    private fun multiTurn() = listOf(
        UIMessage.system("system prompt"),
        UIMessage.user("first question"),
        UIMessage.assistant("first answer"),
        UIMessage.user("second question")
    )

    private fun JsonObject.lastBlockCacheControl(): JsonObject? =
        (this["content"] as? JsonArray)?.lastOrNull()?.jsonObject?.get("cache_control")?.jsonObject

    private fun JsonArray.userMessages(): List<JsonObject> =
        filter { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
            .map { it.jsonObject }

    @Test
    fun `openrouter anthropic model marks system and second-to-last user turn`() {
        val request = buildRequest(
            messages = multiTurn(),
            params = TextGenerationParams(model = Model(modelId = "anthropic/claude-sonnet-4")),
            providerSetting = openRouter()
        )
        val msgs = request["messages"]!!.jsonArray

        // system prefix gets a breakpoint (string content promoted to array form)
        val system = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system" }.jsonObject
        assertEquals("ephemeral", system.lastBlockCacheControl()!!["type"]!!.jsonPrimitive.content)

        // second-to-last user turn gets a breakpoint, the last one does not
        val users = msgs.userMessages()
        assertEquals(2, users.size)
        assertEquals("ephemeral", users[users.size - 2].lastBlockCacheControl()!!["type"]!!.jsonPrimitive.content)
        assertNull(users.last().lastBlockCacheControl())
    }

    @Test
    fun `openrouter gemini and qwen models are also marked`() {
        listOf("google/gemini-2.5-pro", "qwen/qwen3-max").forEach { modelId ->
            val request = buildRequest(
                messages = multiTurn(),
                params = TextGenerationParams(model = Model(modelId = modelId)),
                providerSetting = openRouter()
            )
            val system = request["messages"]!!.jsonArray
                .first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system" }.jsonObject
            assertEquals(
                "cache_control missing for $modelId",
                "ephemeral",
                system.lastBlockCacheControl()!!["type"]!!.jsonPrimitive.content
            )
        }
    }

    @Test
    fun `promptCaching=false adds no cache_control`() {
        val request = buildRequest(
            messages = multiTurn(),
            params = TextGenerationParams(model = Model(modelId = "anthropic/claude-sonnet-4")),
            providerSetting = openRouter(promptCaching = false)
        )
        assertNoCacheControl(request)
    }

    @Test
    fun `every model on openrouter is marked (openrouter strips it for auto-cachers)`() {
        // The gate is host + promptCaching only, no model allow-list: OpenRouter drops the
        // field for providers that cache automatically, so marking openai/ is harmless.
        val request = buildRequest(
            messages = multiTurn(),
            params = TextGenerationParams(model = Model(modelId = "openai/gpt-4o")),
            providerSetting = openRouter()
        )
        val system = request["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system" }.jsonObject
        assertEquals("ephemeral", system.lastBlockCacheControl()!!["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic model on non-openrouter host is untouched`() {
        val request = buildRequest(
            messages = multiTurn(),
            params = TextGenerationParams(model = Model(modelId = "anthropic/claude-sonnet-4")),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1", promptCaching = true)
        )
        assertNoCacheControl(request)
    }

    @Test
    fun `single user turn marks system only`() {
        val request = buildRequest(
            messages = listOf(UIMessage.system("system prompt"), UIMessage.user("only question")),
            params = TextGenerationParams(model = Model(modelId = "anthropic/claude-sonnet-4")),
            providerSetting = openRouter()
        )
        val msgs = request["messages"]!!.jsonArray
        val system = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system" }.jsonObject
        assertEquals("ephemeral", system.lastBlockCacheControl()!!["type"]!!.jsonPrimitive.content)
        // with a single user turn there is no cacheable prefix to mark
        assertNull(msgs.userMessages().single().lastBlockCacheControl())
    }

    private fun assertNoCacheControl(request: JsonObject) {
        request["messages"]!!.jsonArray.forEach { msg ->
            (msg.jsonObject["content"] as? JsonArray)?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }
}
