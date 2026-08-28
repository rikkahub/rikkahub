package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
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

class ChatCompletionsToolSchemaTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `tool without parameters uses empty object schema`() {
        val tool = Tool(
            name = "get_current_time",
            description = "Get the current date and time.",
            execute = { emptyList() },
        )

        val body = buildRequest(tool)
        val function = body["tools"]
            ?.jsonArray
            ?.single()
            ?.jsonObject
            ?.get("function")
            ?.jsonObject
            ?: error("function tool not found")
        val parameters = function["parameters"]?.jsonObject
            ?: error("parameters schema not found")

        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertEquals(JsonObject(emptyMap()), parameters["properties"]?.jsonObject)
        assertFalse(parameters.containsKey("required"))
    }

    private fun buildRequest(tool: Tool): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true

        return method.invoke(
            api,
            listOf(UIMessage.user("Use the get_current_time tool.")),
            TextGenerationParams(
                model = Model(
                    modelId = "test-model",
                    abilities = listOf(ModelAbility.TOOL),
                ),
                tools = listOf(tool),
            ),
            ProviderSetting.OpenAI(),
            false,
        ) as JsonObject
    }
}
