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
    fun `text part serializes with spec kind discriminator`() {
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
        val part = (Json.parseToJsonElement(encoded).jsonObject["parts"] as kotlinx.serialization.json.JsonArray)
            .first().jsonObject

        // A2A-spec canonical discriminator is `kind`, not kotlinx's default `type`.
        assertEquals("text", part["kind"]?.jsonPrimitive?.content)
        assertEquals(null, part["type"])
        assertEquals("hello", part["text"]?.jsonPrimitive?.content)
        assertFalse(encoded.contains("\"file\""))
        assertFalse(encoded.contains("\"data\""))
    }

    @Test
    fun `text part decodes from kind, legacy type, and no discriminator`() {
        // Spec / Hermes form.
        assertEquals(
            "from-kind",
            decodeSinglePartText("""{"kind":"text","text":"from-kind"}"""),
        )
        // Legacy rikkahub form — must keep working for older callers.
        assertEquals(
            "from-type",
            decodeSinglePartText("""{"type":"text","text":"from-type"}"""),
        )
        // Discriminator omitted — tolerate and treat as text.
        assertEquals(
            "no-disc",
            decodeSinglePartText("""{"text":"no-disc"}"""),
        )
    }

    private fun decodeSinglePartText(partJson: String): String {
        val msgJson = """{"messageId":"m","role":"user","parts":[$partJson]}"""
        val message = Json.decodeFromString<A2aMessage>(msgJson)
        return (message.parts.single() as A2aPart.TextPart).text
    }
}
