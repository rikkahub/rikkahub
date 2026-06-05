package me.rerere.ai.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    /**
     * Lenient-restore contract: a single corrupt timestamp must not abort
     * deserialization of the whole persisted object (issue #121). The primary
     * path is ISO-8601; an all-digit string is accepted as epoch-millis for
     * compatibility. On total failure we fall back to a deterministic sentinel
     * [Instant.EPOCH] — NOT Instant.now() — so re-serializing a restored object
     * writes the same value on every load instead of silently drifting to a
     * fresh "now" and corrupting recency/ordering.
     */
    override fun deserialize(decoder: Decoder): Instant {
        val isoString = decoder.decodeString()
        return runCatching { Instant.parse(isoString) }.getOrNull()
            ?: isoString.trim().takeIf { it.matches(Regex("-?\\d+")) }
                ?.let { runCatching { Instant.ofEpochMilli(it.toLong()) }.getOrNull() }
            ?: Instant.EPOCH
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        val isoString = value.toString()
        encoder.encodeString(isoString)
    }
}
