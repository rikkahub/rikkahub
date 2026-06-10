package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the streaming-image SSE wire-shape parse in [parseImageStreamEvent].
 *
 * The OpenAI image endpoints now stream partial previews + a final frame over SSE
 * (`image_generation.partial_image` / `.completed`, and the `image_edit.*` mirror for edits).
 * The parser is the single point that decides, per SSE frame:
 *   - whether the frame is a partial preview or the finalized image (`partial` flag),
 *   - which preview slot the partial belongs to (`partialImageIndex`),
 *   - the image mime type from `output_format`,
 *   - and that unrelated / payload-less frames are dropped (returned null) rather than
 *     surfaced as empty images to the UI.
 *
 * These are pure, network-free assertions: the EventSource/callbackFlow wiring is left to the
 * existing SSE infra; this test pins ONLY the per-frame decode the UI's partial-preview loop
 * depends on.
 */
class OpenAIProviderImageStreamTest {

    private val genPartial = "image_generation.partial_image"
    private val genCompleted = "image_generation.completed"
    private val editPartial = "image_edit.partial_image"
    private val editCompleted = "image_edit.completed"

    private fun parse(
        event: JsonObject,
        partialEventType: String = genPartial,
        completedEventType: String = genCompleted,
        eventType: String? = null,
    ) = parseImageStreamEvent(event, partialEventType, completedEventType, eventType)

    @Test
    fun `partial frame is flagged partial and carries index and jpeg mime`() {
        val event = buildJsonObject {
            put("type", genPartial)
            put("b64_json", "AAAA")
            put("output_format", "jpeg")
            put("partial_image_index", 1)
        }

        val item = parse(event)

        assertTrue(item != null)
        assertEquals("AAAA", item!!.data)
        assertTrue(item.partial)
        assertEquals(1, item.partialImageIndex)
        assertEquals("image/jpeg", item.mimeType)
    }

    @Test
    fun `completed frame is not partial and maps webp mime with no index`() {
        val event = buildJsonObject {
            put("type", genCompleted)
            put("b64_json", "BBBB")
            put("output_format", "webp")
        }

        val item = parse(event)

        assertTrue(item != null)
        assertEquals("BBBB", item!!.data)
        assertTrue(!item.partial)
        assertNull(item.partialImageIndex)
        assertEquals("image/webp", item.mimeType)
    }

    @Test
    fun `frame of unrelated type is dropped`() {
        val event = buildJsonObject {
            put("type", "image_generation.in_progress")
            put("b64_json", "CCCC")
        }

        assertNull(parse(event))
    }

    @Test
    fun `matching type with missing b64_json is dropped`() {
        val event = buildJsonObject {
            put("type", genPartial)
            put("partial_image_index", 0)
        }

        assertNull(parse(event))
    }

    @Test
    fun `absent output_format defaults to png`() {
        val event = buildJsonObject {
            put("type", genCompleted)
            put("b64_json", "DDDD")
        }

        val item = parse(event)

        assertTrue(item != null)
        assertEquals("image/png", item!!.mimeType)
    }

    @Test
    fun `type falls back to SSE event arg when body omits type`() {
        val event = buildJsonObject {
            put("b64_json", "EEEE")
            put("output_format", "jpg")
        }

        val item = parse(event, eventType = genPartial)

        assertTrue(item != null)
        assertTrue(item!!.partial)
        assertEquals("image/jpeg", item.mimeType)
    }

    // Pins the per-image wire CONTRACT over a full stream, not just isolated frames: a sequence of
    // partial previews followed by the finalizing `completed` frame must decode, in order, to N
    // partials (flagged partial, carrying their progressive index) then one final (not partial, no
    // index). This is the sequence the collector's partial-preview-then-persist loop consumes. The
    // EventSource/callbackFlow transport itself stays instrumented-only; this covers the decode.
    @Test
    fun `a partial-then-completed sequence decodes to ordered partials then a final`() {
        val frames = listOf(
            buildJsonObject {
                put("type", genPartial); put("b64_json", "P0"); put("output_format", "png"); put("partial_image_index", 0)
            },
            buildJsonObject {
                put("type", genPartial); put("b64_json", "P1"); put("output_format", "png"); put("partial_image_index", 1)
            },
            buildJsonObject {
                put("type", "image_generation.in_progress"); put("b64_json", "SKIP") // unrelated frame, dropped
            },
            buildJsonObject {
                put("type", genCompleted); put("b64_json", "FINAL"); put("output_format", "png")
            },
        )

        val decoded = frames.mapNotNull { parse(it) }

        // The unrelated in_progress frame is dropped; the two partials + final survive in order.
        assertEquals(listOf("P0", "P1", "FINAL"), decoded.map { it.data })
        assertEquals(listOf(true, true, false), decoded.map { it.partial })
        assertEquals(listOf(0, 1, null), decoded.map { it.partialImageIndex })
        // Exactly one finalizing frame ends the image; the collector relies on this to persist + advance.
        assertEquals(1, decoded.count { !it.partial })
    }

    @Test
    fun `edit partial frame uses the edit event types`() {
        val event = buildJsonObject {
            put("type", editPartial)
            put("b64_json", "FFFF")
            put("output_format", "png")
            put("partial_image_index", 0)
        }

        val item = parse(
            event = event,
            partialEventType = editPartial,
            completedEventType = editCompleted,
        )

        assertTrue(item != null)
        assertTrue(item!!.partial)
        assertEquals(0, item.partialImageIndex)
    }

    /**
     * Regression for the DALL-E 400 (issue #231, slice 2 review): stream/partial_images is gated on
     * the gpt-image-* family. Only gpt-image-* may take the streaming path; dall-e-* and arbitrary
     * IMAGE-typed model IDs must fall back to the one-shot request, which OpenAI accepts.
     */
    @Test
    fun `gpt-image family supports streaming`() {
        assertTrue(supportsImageStreaming("gpt-image-1"))
        assertTrue(supportsImageStreaming("gpt-image-2"))
        assertTrue(supportsImageStreaming("GPT-Image-1"))
        assertTrue(supportsImageStreaming("gpt-image-1-2025-10-06"))
    }

    @Test
    fun `dall-e and arbitrary models do not support streaming`() {
        assertTrue(!supportsImageStreaming("dall-e-2"))
        assertTrue(!supportsImageStreaming("dall-e-3"))
        assertTrue(!supportsImageStreaming("my-custom-image-model"))
        assertTrue(!supportsImageStreaming("flux-pro"))
    }
}
