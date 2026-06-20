package me.rerere.rikkahub.web.a2a

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire serializer for [A2aPart].
 *
 * The A2A spec discriminates Part objects with a `kind` field ("text" | "file" |
 * "data"); kotlinx-serialization's default discriminator for a sealed class is a
 * `type` field. To interoperate with spec-compliant peers (Hermes, LangChain,
 * CrewAI, Google ADK, …) we make `kind` canonical on the wire while still
 * accepting the legacy `type` discriminator rikkahub previously emitted. The
 * dual-discriminator quirk is isolated here so the rest of the protocol model
 * stays plain kotlinx data classes.
 */
internal object A2aPartSerializer : KSerializer<A2aPart> {
    private const val KIND = "kind"
    private const val LEGACY_KIND = "type"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("me.rerere.rikkahub.web.a2a.A2aPart")

    override fun deserialize(decoder: Decoder): A2aPart {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("A2aPart can only be read from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val kind = (obj[KIND] ?: obj[LEGACY_KIND])?.jsonPrimitive?.content ?: "text"
        return when (kind) {
            "text" -> input.json.decodeFromJsonElement(
                A2aPart.TextPart.serializer(),
                JsonObject(obj - KIND - LEGACY_KIND),
            )
            else -> throw SerializationException("unsupported A2A part kind: $kind")
        }
    }

    override fun serialize(encoder: Encoder, value: A2aPart) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("A2aPart can only be written as JSON")
        val body = when (value) {
            is A2aPart.TextPart ->
                output.json.encodeToJsonElement(A2aPart.TextPart.serializer(), value).jsonObject
        }
        output.encodeJsonElement(JsonObject(mapOf(KIND to JsonPrimitive("text")) + body))
    }
}
