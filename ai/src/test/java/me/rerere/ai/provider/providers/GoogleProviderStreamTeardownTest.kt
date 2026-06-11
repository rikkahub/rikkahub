package me.rerere.ai.provider.providers

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Gemini stream teardown decision in [googleStreamFrameOutcome].
 *
 * The bug (issue #240): the inline onEvent path detected `promptFeedback.blockReason`, called
 * `close(error)` — and then FELL THROUGH to build + `trySend()` a content chunk from any
 * `candidates` present on the SAME frame. A mid-stream SAFETY/RECITATION block can arrive on a
 * frame that ALSO carries a partial candidate, so a terminated stream could still emit content
 * after its terminal close, racing the error.
 *
 * The invariant: a frame that terminates the stream must NOT also emit content. The decision is
 * now a single pure outcome (Terminate | Emit | Skip) and the candidates-to-send are carried ONLY
 * on the Emit variant, so onEvent's `trySend(...)` is reachable ONLY through the Emit branch — the
 * close-then-fall-through-and-emit path cannot exist by construction (a Terminate outcome carries
 * no candidates). These assertions are pure and network-free (no EventSource/callbackFlow, no
 * android.util.Log on this path), matching the documented :ai unit-test limitation in
 * GoogleProviderResponseTest.
 */
class GoogleProviderStreamTeardownTest {

    @Test
    fun `frame with blockReason AND candidates terminates and carries no sendable candidates`() {
        // The exact racy shape: a SAFETY block alongside a partial candidate content part.
        val frame = buildJsonObject {
            putJsonObject("promptFeedback") {
                put("blockReason", "SAFETY")
            }
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "partial leak") })
                        }
                    }
                })
            }
        }

        val outcome = googleStreamFrameOutcome(frame)

        assertTrue(
            "blockReason must terminate even when candidates are present",
            outcome is GoogleStreamFrame.Terminate
        )
        // The Terminate variant has no `candidates` field at all, so onEvent — which now reads the
        // candidates to send only off GoogleStreamFrame.Emit — has nothing to trySend on this frame.
        // This is the structural guard the old object-Emit + inline-candidates path lacked.
        assertFalse(
            "a terminating frame must not also emit content (the missing-return bug)",
            outcome is GoogleStreamFrame.Emit
        )
        assertEquals("SAFETY", (outcome as GoogleStreamFrame.Terminate).reason)
    }

    @Test
    fun `frame with only blockReason terminates`() {
        val frame = buildJsonObject {
            putJsonObject("promptFeedback") {
                put("blockReason", "RECITATION")
            }
        }

        val outcome = googleStreamFrameOutcome(frame)

        assertTrue(outcome is GoogleStreamFrame.Terminate)
        assertEquals("RECITATION", (outcome as GoogleStreamFrame.Terminate).reason)
    }

    @Test
    fun `frame with candidates and no feedback emits`() {
        val frame = buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "hello") })
                        }
                    }
                })
            }
        }

        val outcome = googleStreamFrameOutcome(frame)
        assertTrue(outcome is GoogleStreamFrame.Emit)
        // The candidates onEvent will send are carried on the Emit outcome itself — the only path
        // that yields anything to trySend.
        assertEquals(1, (outcome as GoogleStreamFrame.Emit).candidates.size)
    }

    @Test
    fun `frame with no candidates and no feedback is skipped`() {
        val frame = buildJsonObject {
            putJsonObject("usageMetadata") {
                put("totalTokenCount", 5)
            }
        }

        assertTrue(googleStreamFrameOutcome(frame) is GoogleStreamFrame.Skip)
    }

    @Test
    fun `frame with empty candidates array and no feedback is skipped`() {
        val frame = buildJsonObject {
            putJsonArray("candidates") {}
        }

        assertTrue(googleStreamFrameOutcome(frame) is GoogleStreamFrame.Skip)
    }
}
