package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeProviderPromptCacheTest {
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

    private fun dummyTool(): Tool {
        return Tool(
            name = "dummy_tool",
            description = "dummy",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

    private fun reasoningModel(modelId: String): Model {
        return Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING))
    }

    private fun reasoningRequest(modelId: String, thinkingBudget: Int?): JsonObject {
        return buildRequest(
            providerSetting = ProviderSetting.Claude(),
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = reasoningModel(modelId),
                thinkingBudget = thinkingBudget
            )
        )
    }

    @Test
    fun `promptCaching=false should not add cache_control anywhere`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = false)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should not have cache_control
        val system = request["system"]?.jsonArray
        assertNotNull(system)
        assertTrue(system!!.isNotEmpty())
        assertNull(system.last().jsonObject["cache_control"])

        // tools should not have cache_control
        val tools = request["tools"]?.jsonArray
        assertNotNull(tools)
        assertTrue(tools!!.isNotEmpty())
        assertNull(tools.last().jsonObject["cache_control"])

        // messages should not have cache_control
        val msgs = request["messages"]!!.jsonArray
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }

    @Test
    fun `promptCaching=true should add cache_control to last system block and last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should have cache_control
        val system = request["system"]!!.jsonArray
        val systemCacheControl = system.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", systemCacheControl["type"]!!.jsonPrimitive.content)

        // tools should have cache_control
        val tools = request["tools"]!!.jsonArray
        val toolsCacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", toolsCacheControl["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promptCaching=true without system should add cache_control to last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        assertNull(request["system"])

        val tools = request["tools"]!!.jsonArray
        val cacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promptCaching=true should add cache_control to second-to-last real user message`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question"),
            UIMessage.assistant("second answer"),
            UIMessage.user("third question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // Find all real user messages (not tool_result)
        val userMsgIndices = msgs.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.content == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // Should have 3 real user messages
        assertEquals(3, userMsgIndices.size)

        // Second-to-last (index 1 in userMsgIndices) should have cache_control
        val targetMsg = msgs[userMsgIndices[1]].jsonObject
        val content = targetMsg["content"]!!.jsonArray
        val cacheControl = content.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)

        // Last user message should NOT have cache_control
        val lastMsg = msgs[userMsgIndices[2]].jsonObject
        val lastContent = lastMsg["content"]!!.jsonArray
        assertNull(lastContent.last().jsonObject["cache_control"])

        // First user message should NOT have cache_control
        val firstMsg = msgs[userMsgIndices[0]].jsonObject
        val firstContent = firstMsg["content"]!!.jsonArray
        assertNull(firstContent.last().jsonObject["cache_control"])
    }

    @Test
    fun `promptCaching=true with only one user message should not add cache_control to messages`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("only question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // Only one user message, so no cache_control on messages
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }

    @Test
    fun `legacy Claude auto should not send adaptive thinking`() {
        val request = reasoningRequest(
            modelId = "claude-sonnet-4-5-20250929",
            thinkingBudget = -1
        )

        assertNull(request["thinking"])
        assertNull(request["output_config"])
    }

    @Test
    fun `legacy Claude preset budget should keep manual thinking`() {
        val request = reasoningRequest(
            modelId = "claude-sonnet-4-5-20250929",
            thinkingBudget = 1024
        )

        val thinking = request["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(1024, thinking["budget_tokens"]!!.jsonPrimitive.int)
        assertNull(request["output_config"])
    }

    @Test
    fun `Claude 4_6 auto should use adaptive thinking without effort`() {
        val request = reasoningRequest(
            modelId = "claude-sonnet-4-6-20251001",
            thinkingBudget = -1
        )

        val thinking = request["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
        assertNull(request["output_config"])
    }

    @Test
    fun `Claude 4_6 preset reasoning levels should map to adaptive effort`() {
        val levels = listOf(
            1024 to "low",
            16_000 to "medium",
            32_000 to "high"
        )

        levels.forEach { (thinkingBudget, effort) ->
            val request = reasoningRequest(
                modelId = "claude-opus-4-6-20251001",
                thinkingBudget = thinkingBudget
            )

            val thinking = request["thinking"]!!.jsonObject
            val outputConfig = request["output_config"]!!.jsonObject
            assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
            assertEquals(effort, outputConfig["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `Claude 4_6 custom budget should keep manual thinking`() {
        val request = reasoningRequest(
            modelId = "claude-opus-4-6-20251001",
            thinkingBudget = 5000
        )

        val thinking = request["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(5000, thinking["budget_tokens"]!!.jsonPrimitive.int)
        assertNull(request["output_config"])
    }
}
