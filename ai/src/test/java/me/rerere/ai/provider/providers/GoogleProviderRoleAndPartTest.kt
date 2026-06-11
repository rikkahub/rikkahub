package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for role/part tolerance in GoogleProvider parsing (issue #241).
 *
 * Bug class: a parser fed an unexpected-but-valid value `error()`s out of the parse path instead
 * of degrading. `googleRoleToCommonRole` threw on any role outside user/system/model;
 * `parseMessagePart` threw on any part kind outside text/functionCall/inlineData — and because the
 * caller mapped over `content.parts`, one unknown part dropped the WHOLE frame, losing valid
 * sibling text parts. Google adds part kinds over time (executableCode, codeExecutionResult, ...).
 */
class GoogleProviderRoleAndPartTest {

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    private fun invokeGoogleRoleToCommonRole(role: String): MessageRole {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "googleRoleToCommonRole",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, role) as MessageRole
    }

    private fun invokeParseMessagePart(jsonObject: JsonObject): UIMessagePart? {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseMessagePart",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, jsonObject) as UIMessagePart?
    }

    private fun invokeParseMessage(message: JsonObject): UIMessage {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseMessage",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, message) as UIMessage
    }

    @Test
    fun `known roles map correctly`() {
        assertEquals(MessageRole.USER, invokeGoogleRoleToCommonRole("user"))
        assertEquals(MessageRole.SYSTEM, invokeGoogleRoleToCommonRole("system"))
        assertEquals(MessageRole.ASSISTANT, invokeGoogleRoleToCommonRole("model"))
    }

    @Test
    fun `unknown role degrades to ASSISTANT instead of throwing`() {
        assertEquals(MessageRole.ASSISTANT, invokeGoogleRoleToCommonRole("oracle"))
    }

    @Test
    fun `unknown part type returns null instead of throwing`() {
        val part = buildJsonObject {
            putJsonObject("executableCode") {
                put("language", "PYTHON")
                put("code", "print(1)")
            }
        }
        assertNull(invokeParseMessagePart(part))
    }

    @Test
    fun `frame with unknown role and unknown part keeps sibling text`() {
        // P2 metamorphic: an unknown role AND an unknown part type must still yield the valid
        // sibling text — not an empty/dropped frame.
        val frame = buildJsonObject {
            put("role", "oracle")
            putJsonObject("content") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", "hello") })
                    add(buildJsonObject {
                        putJsonObject("executableCode") {
                            put("language", "PYTHON")
                            put("code", "print(1)")
                        }
                    })
                }
            }
        }

        val message = invokeParseMessage(frame)

        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals(1, message.parts.size)
        val text = message.parts.single() as UIMessagePart.Text
        assertEquals("hello", text.text)
    }
}
