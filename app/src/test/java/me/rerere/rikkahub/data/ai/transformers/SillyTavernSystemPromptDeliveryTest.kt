package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SillyTavernSystemPromptDeliveryTest {
    private fun createTemplate(useSystemPrompt: Boolean) = SillyTavernPromptTemplate(
        useSystemPrompt = useSystemPrompt,
        prompts = listOf(
            SillyTavernPromptItem(identifier = "main", content = "ST Main"),
            SillyTavernPromptItem(identifier = "chatHistory", marker = true),
        ),
        orderedPromptIds = listOf("main", "chatHistory"),
    )

    private fun transformForProvider(useSystemPrompt: Boolean): List<UIMessage> = runBlocking {
        listOf(UIMessage.user("Hello")).transforms(
            transformers = listOf(SillyTavernPromptTransformer),
            context = ContextWrapper(null),
            model = Model(modelId = "test-model"),
            assistant = Assistant(),
            settings = Settings(
                stPresetEnabled = true,
                stPresetTemplate = createTemplate(useSystemPrompt),
            ),
        )
    }

    private fun buildClaudeRequest(messages: List<UIMessage>): JsonObject {
        val provider = ClaudeProvider(OkHttpClient())
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            ProviderSetting.Claude(),
            messages,
            TextGenerationParams(model = Model(modelId = "claude-test")),
            false,
        ) as JsonObject
    }

    private fun buildGoogleRequest(messages: List<UIMessage>): JsonObject {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            messages,
            TextGenerationParams(model = Model(modelId = "gemini-test")),
        ) as JsonObject
    }

    private fun buildOpenAIRequest(messages: List<UIMessage>): JsonObject {
        val api = ResponseAPI(OkHttpClient())
        val method = ResponseAPI::class.java.declaredMethods.first { method ->
            method.name.startsWith("buildRequestBody") &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        ProviderSetting.OpenAI::class.java,
                        List::class.java,
                        TextGenerationParams::class.java,
                        Boolean::class.javaPrimitiveType!!,
                    )
                )
        }
        method.isAccessible = true
        return method.invoke(
            api,
            ProviderSetting.OpenAI(),
            messages,
            TextGenerationParams(model = Model(modelId = "gpt-test")),
            false,
        ) as JsonObject
    }

    @Test
    fun `claude request should omit native system field when ST useSystemPrompt is off`() {
        val messages = transformForProvider(useSystemPrompt = false)
        assertEquals(listOf(MessageRole.USER, MessageRole.USER), messages.map { it.role })

        val request = buildClaudeRequest(messages)

        assertNull(request["system"])
        val requestMessages = request["messages"]!!.jsonArray
        assertEquals(listOf("user", "user"), requestMessages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            "ST Main",
            requestMessages[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `claude request should keep native system field when ST useSystemPrompt is on`() {
        val messages = transformForProvider(useSystemPrompt = true)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), messages.map { it.role })

        val request = buildClaudeRequest(messages)

        val system = request["system"]?.jsonArray
        assertNotNull(system)
        assertEquals("ST Main", system!![0].jsonObject["text"]!!.jsonPrimitive.content)
        val requestMessages = request["messages"]!!.jsonArray
        assertEquals(listOf("user"), requestMessages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `google request should omit native systemInstruction when ST useSystemPrompt is off`() {
        val messages = transformForProvider(useSystemPrompt = false)
        assertEquals(listOf(MessageRole.USER, MessageRole.USER), messages.map { it.role })

        val request = buildGoogleRequest(messages)

        assertNull(request["systemInstruction"])
        val contents = request["contents"]!!.jsonArray
        assertEquals(listOf("user", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            "ST Main",
            contents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `google request should keep native systemInstruction when ST useSystemPrompt is on`() {
        val messages = transformForProvider(useSystemPrompt = true)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), messages.map { it.role })

        val request = buildGoogleRequest(messages)

        val systemInstruction = request["systemInstruction"]?.jsonObject
        assertNotNull(systemInstruction)
        assertEquals(
            "ST Main",
            systemInstruction!!["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
        val contents = request["contents"]!!.jsonArray
        assertEquals(listOf("user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `openai response request should omit instructions when ST useSystemPrompt is off`() {
        val messages = transformForProvider(useSystemPrompt = false)
        assertEquals(listOf(MessageRole.USER, MessageRole.USER), messages.map { it.role })

        val request = buildOpenAIRequest(messages)

        assertNull(request["instructions"])
        val input = request["input"]!!.jsonArray
        assertEquals(listOf("user", "user"), input.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("ST Main", input[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai response request should keep instructions when ST useSystemPrompt is on`() {
        val messages = transformForProvider(useSystemPrompt = true)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), messages.map { it.role })

        val request = buildOpenAIRequest(messages)

        assertEquals("ST Main", request["instructions"]!!.jsonPrimitive.content)
        val input = request["input"]!!.jsonArray
        assertEquals(listOf("user"), input.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }
}
