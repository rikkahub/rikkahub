package me.rerere.rikkahub.ui.pages.imggen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the streaming-preview temp-file leak (issue #231, slice 2 review).
 *
 * The previous collector tracked the live preview in a single `var previewFile: File?`. When
 * numOfImages > 1, each output image produces its own preview filename; the single var only ever
 * held the most-recently-written one, so previews for earlier output images were never deletable —
 * they leaked until the next app start purged appTempFolder. It also had no terminal cleanup, so a
 * flow ending after a partial (user cancel / SSE failure) leaked its last preview too.
 *
 * [PreviewSlots] is the data-structure fix: per-OUTPUT-image tracking + [PreviewSlots.drain]
 * surfacing every outstanding file for the collector's terminal `finally`. The collector keys slots
 * by the output-image index (the count of finalized images so far), NOT by the frame's
 * `partial_image_index` — that wire field is a single image's progressive-refinement counter and
 * resets to 0 per output image, so keying on it would collide distinct images on slot 0. These
 * assertions exercise the keyed-map contract directly: distinct keys retain distinct files (a
 * single-var tracker cannot), [take] is per-key, [put] replaces, [drain] empties.
 */
class PreviewSlotsTest {

    private fun fakeFile(name: String) = File("/tmp/$name")

    @Test
    fun `distinct indices retain distinct outstanding previews`() {
        val slots = PreviewSlots()
        val a = fakeFile("imggen_0.png")
        val b = fakeFile("imggen_1.png")

        slots.put(0, a)
        slots.put(1, b)

        // The single-var design would have lost slot 0 here; both must survive.
        val drained = slots.drain()
        assertEquals(setOf(a, b), drained.toSet())
        assertEquals(2, drained.size)
    }

    @Test
    fun `take removes only the requested index and returns its file`() {
        val slots = PreviewSlots()
        val a = fakeFile("imggen_0.png")
        val b = fakeFile("imggen_1.png")
        slots.put(0, a)
        slots.put(1, b)

        assertEquals(a, slots.take(0))
        assertNull(slots.take(0))
        // Slot 1 is untouched by taking slot 0.
        assertEquals(listOf(b), slots.drain())
    }

    @Test
    fun `put replaces the prior handle for the same index`() {
        val slots = PreviewSlots()
        val first = fakeFile("imggen_0_a.png")
        val second = fakeFile("imggen_0_b.png")
        slots.put(0, first)
        slots.put(0, second)

        assertEquals(listOf(second), slots.drain())
    }

    @Test
    fun `drain empties the tracker so a second drain yields nothing`() {
        val slots = PreviewSlots()
        slots.put(0, fakeFile("imggen_0.png"))
        slots.drain()

        assertTrue(slots.drain().isEmpty())
    }
}

/**
 * Pins the production preview-slot keying the collector actually uses (issue #231, slice 2).
 *
 * [collectImageGeneration] keys each partial preview by [previewSlotKey] of `finalIndex` — the count
 * of FINALIZED images so far — NOT by the frame's `partial_image_index` (a single image's
 * progressive-refinement counter that resets to 0 per output image, so keying on it would collide
 * distinct images on slot 0 and leak the earlier image's temp file).
 *
 * This relies on the documented/observed SEQUENTIAL delivery: the endpoint streams ALL of output
 * image k's partials, then its `completed` frame, before image k+1 begins. Under that ordering
 * `finalIndex` equals the ordinal of the image currently streaming, so each image's previews occupy
 * their own slot and are drained on terminal cleanup. The wire provides NO per-partial output-image
 * id, so an INTERLEAVED ordering (left undefined by the API spec) is not distinguishable here — the
 * design depends on sequential delivery, and these tests pin THAT contract rather than pretending to
 * defend against interleaving.
 */
class PreviewSlotKeyTest {

    private data class Frame(val partial: Boolean)

    /**
     * Replay [collectImageGeneration]'s slot bookkeeping (IO stripped) over a SEQUENTIAL frame stream
     * using the production key `previewSlotKey(finalIndex)`. Returns, per slot, the set of image
     * ordinals (the finalIndex in effect = the streaming image) that used it — the collector's
     * invariant is that a slot belongs to exactly one output image.
     */
    private fun slotOwners(frames: List<Frame>): Map<Int, Set<Int>> {
        val owners = mutableMapOf<Int, MutableSet<Int>>()
        var finalIndex = 0
        frames.forEach { frame ->
            if (frame.partial) {
                owners.getOrPut(previewSlotKey(finalIndex)) { mutableSetOf() }.add(finalIndex)
            } else {
                finalIndex++
            }
        }
        return owners
    }

    // Two output images delivered sequentially: image 0's two partials + its completed frame, then
    // image 1's two partials + its completed frame.
    private val sequentialTwoImages = listOf(
        Frame(partial = true),   // image 0 partial (finalIndex 0)
        Frame(partial = true),   // image 0 partial (finalIndex 0)
        Frame(partial = false),  // image 0 completed -> finalIndex 1
        Frame(partial = true),   // image 1 partial (finalIndex 1)
        Frame(partial = true),   // image 1 partial (finalIndex 1)
        Frame(partial = false),  // image 1 completed -> finalIndex 2
    )

    @Test
    fun `sequential delivery gives each output image its own slot`() {
        val owners = slotOwners(sequentialTwoImages)
        // No slot is shared by more than one output image: each image refines its own preview safely.
        assertTrue("a preview slot must belong to exactly one output image", owners.values.all { it.size == 1 })
        assertEquals(setOf(0), owners[previewSlotKey(0)])
        assertEquals(setOf(1), owners[previewSlotKey(1)])
    }

    @Test
    fun `previewSlotKey is the finalized-image ordinal`() {
        // The collector keys by the count of finalized images, so the key IS that count (identity).
        // This is the property that makes a partial belong to the image currently streaming.
        assertEquals(0, previewSlotKey(0))
        assertEquals(1, previewSlotKey(1))
        assertEquals(7, previewSlotKey(7))
    }
}
