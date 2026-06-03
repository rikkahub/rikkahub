package me.rerere.ai.ui

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.util.json
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TARGET 6: kotlinx roundtrip for List<UIMessage> across the serialized UIMessagePart subtypes
 * (Text, Image, Reasoning, Document, Tool-with-output) using the :ai module's internal `json`.
 *
 * Property (Roundtrip): decode(encode(msgs)) == msgs.
 */
class UIMessageRoundtripTest {

    @Test
    fun `List of UIMessage survives kotlinx encode-decode roundtrip`() {
        runBlocking {
            checkAll(200, UIMessageArbs.arbMessages) { msgs ->
                val encoded = json.encodeToString<List<UIMessage>>(msgs)
                val decoded = json.decodeFromString<List<UIMessage>>(encoded)
                assertEquals(msgs, decoded)
            }
        }
    }

    /**
     * The @Transient streamIndex on Tool is a streaming-only carrier: it must never affect equals
     * nor appear in the serialized form. Setting it on a finalized Tool and roundtripping must yield
     * an equal Tool whose decoded streamIndex is null.
     */
    @Test
    fun `Tool streamIndex is excluded from equality and serialization`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "search",
            input = """{"q":"x"}""",
            output = listOf(UIMessagePart.Text("done")),
        ).also { it.streamIndex = 7 }

        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))

        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<UIMessage>(encoded)

        assertEquals(message, decoded)
        val decodedTool = decoded.parts.single() as UIMessagePart.Tool
        assertNull(decodedTool.streamIndex)
    }

    /**
     * An unfinished Reasoning (finishedAt == null) must survive the encode-decode roundtrip as
     * unfinished. The :ai json uses explicitNulls=false + encodeDefaults=true: a null finishedAt is
     * omitted on encode, so on decode the constructor DEFAULT is used. If that default is a non-null
     * Clock.System.now(), the null is silently resurrected into a fresh timestamp and the
     * "still reasoning" state is lost. The default must be null so absent decodes back to null.
     */
    @Test
    fun `unfinished Reasoning keeps null finishedAt across roundtrip`() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thinking...",
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            finishedAt = null,
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning))

        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<UIMessage>(encoded)

        val decodedReasoning = decoded.parts.single() as UIMessagePart.Reasoning
        assertNull(decodedReasoning.finishedAt)
        assertEquals(message, decoded)
    }
}
