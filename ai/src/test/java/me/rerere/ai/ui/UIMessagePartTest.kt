package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessagePartTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server tool round trip preserves provider payload`() {
        val part: UIMessagePart = UIMessagePart.ServerTool(
            toolCallId = "srvtoolu_123",
            toolName = "web_search",
            input = buildJsonObject { put("query", "Kotlin serialization") },
            output = buildJsonArray {
                add(buildJsonObject {
                    put("url", "https://example.com")
                    put("encrypted_content", "encrypted")
                })
            },
            status = ServerToolStatus.COMPLETED,
            metadata = buildJsonObject { put("provider", "claude") },
        )

        val encoded = json.encodeToString(part)
        val encodedObject = json.parseToJsonElement(encoded).jsonObject
        val restored = json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.ServerTool

        assertEquals("server_tool", encodedObject["type"]?.jsonPrimitive?.content)
        assertEquals("completed", encodedObject["status"]?.jsonPrimitive?.content)
        assertEquals(part, restored)
        assertTrue(restored.isFinished)
    }

    @Test
    fun `server tool tracks in progress state`() {
        val part = UIMessagePart.ServerTool(
            toolCallId = "ws_123",
            toolName = "web_search",
            status = ServerToolStatus.IN_PROGRESS,
        )

        assertEquals(ServerToolStatus.IN_PROGRESS, part.status)
        assertFalse(part.isFinished)
    }
}
