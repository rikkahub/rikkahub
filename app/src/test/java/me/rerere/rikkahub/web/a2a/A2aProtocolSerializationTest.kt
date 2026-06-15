package me.rerere.rikkahub.web.a2a

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class A2aProtocolSerializationTest {

    @Test
    fun `json rpc request and result round-trip`() {
        val request = JsonRpcRequest(
            id = JsonPrimitive("123"),
            method = "message/send",
            params = JsonObject(mapOf("skillId" to JsonPrimitive("spawn"))),
        )
        val requestJson = Json.encodeToString(request)
        val decodedRequest = Json.decodeFromString<JsonRpcRequest>(requestJson)
        assertEquals(request, decodedRequest)

        val success = JsonRpcSuccess(
            id = JsonPrimitive("123"),
            result = JsonObject(emptyMap()),
        )
        val successJson = Json.encodeToString(success)
        val decodedSuccess = Json.decodeFromString<JsonRpcSuccess>(successJson)
        assertEquals(success, decodedSuccess)

        val failure = JsonRpcFailure(
            id = JsonPrimitive("123"),
            error = JsonRpcError(
                code = -32600,
                message = "Invalid request",
                data = JsonObject(emptyMap()),
            ),
        )
        val failureJson = Json.encodeToString(failure)
        val decodedFailure = Json.decodeFromString<JsonRpcFailure>(failureJson)
        assertEquals(failure, decodedFailure)
    }

    @Test
    fun `text part serializes as text discriminator only`() {
        val message = A2aMessage(
            messageId = "msg-1",
            role = A2aRole.USER,
            parts = listOf(
                A2aPart.TextPart(
                    text = "hello",
                    metadata = JsonObject(mapOf("origin" to JsonPrimitive("unit"))),
                ),
            ),
        )
        val encoded = Json.encodeToString(message)
        val parsed = Json.parseToJsonElement(encoded).jsonObject
        val partType = (parsed["parts"] as kotlinx.serialization.json.JsonArray).first().jsonObject["type"]?.jsonPrimitive?.content

        assertEquals("text", partType)
        assertFalse(encoded.contains("\"file\""))
        assertFalse(encoded.contains("\"data\""))
    }
}
