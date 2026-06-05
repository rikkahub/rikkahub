package me.rerere.ai.util

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class InstantSerializerTest {

    @Serializable
    private data class Holder(
        @Serializable(with = InstantSerializer::class) val ts: Instant,
    )

    @Serializable
    private data class TwoStamps(
        @Serializable(with = InstantSerializer::class) val good: Instant,
        @Serializable(with = InstantSerializer::class) val bad: Instant,
    )

    private fun decode(raw: String): Instant =
        json.decodeFromString(Holder.serializer(), """{"ts":"$raw"}""").ts

    @Test
    fun `malformed string falls back to EPOCH`() {
        assertEquals(Instant.EPOCH, decode("not-a-date"))
    }

    @Test
    fun `empty string falls back to EPOCH`() {
        assertEquals(Instant.EPOCH, decode(""))
    }

    @Test
    fun `one bad timestamp does not abort the whole object`() {
        val result = json.decodeFromString(
            TwoStamps.serializer(),
            """{"good":"2023-11-14T22:13:20Z","bad":"garbage"}""",
        )
        assertEquals(Instant.parse("2023-11-14T22:13:20Z"), result.good)
        assertEquals(Instant.EPOCH, result.bad)
    }

    @Test
    fun `valid ISO-8601 round-trips exactly`() {
        val original = Instant.parse("2023-11-14T22:13:20.123Z")
        val encoded = json.encodeToString(Holder.serializer(), Holder(original))
        val decoded = json.decodeFromString(Holder.serializer(), encoded).ts
        assertEquals(original, decoded)
    }

    @Test
    fun `epoch-millis numeric string is accepted as compat format`() {
        assertEquals(Instant.ofEpochMilli(1700000000000L), decode("1700000000000"))
    }

    @Test
    fun `sentinel is deterministic and idempotent across re-serialization`() {
        val first = decode("garbage")
        val reEncoded = json.encodeToString(Holder.serializer(), Holder(first))
        val second = json.decodeFromString(Holder.serializer(), reEncoded).ts
        assertEquals(Instant.EPOCH, first)
        assertEquals(first, second)
    }
}
