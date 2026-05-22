package me.rerere.locallm.litert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-decision-function tests for [LiteRtProvider]'s image-forwarding gate.
 *
 * [decideImageForwarding] guards against a hard native crash. When a vision-capable model is
 * loaded but the device's GPU vision executor fails to initialise, [LiteRtRuntime.ensureLoaded]
 * falls back to a TEXT-ONLY engine (see [LiteRtRuntime.LoadOutcome.visionEnabled] /
 * [LiteRtRuntime.LoadOutcome.visionFellBackToTextOnly]). Forwarding image bytes to an engine
 * that has no vision executor null-derefs inside `liblitertlm_jni.so` -> SIGSEGV. The gate
 * MUST therefore key off the ACTUAL post-load vision state, never the pre-load estimate (which
 * is still `true` at the moment of the doomed first load).
 */
class LiteRtProviderTest {

    @Test
    fun `forwards images when vision is live post-load`() {
        val d = decideImageForwarding(
            modelImageCapable = true,
            visionEnabledPostLoad = true,
            userSentImages = true,
        )
        assertTrue("vision live -> forward", d.forwardImages)
        assertFalse("nothing dropped when we forward", d.noteImagesDropped)
    }

    @Test
    fun `does NOT forward images when vision fell back to text-only (the crash case)`() {
        // Vision-capable model, but the GPU vision executor failed and the engine loaded
        // text-only. Forwarding here is the SIGSEGV. Drop the images, and flag a user note.
        val d = decideImageForwarding(
            modelImageCapable = true,
            visionEnabledPostLoad = false,
            userSentImages = true,
        )
        assertFalse("vision not live -> never forward", d.forwardImages)
        assertTrue("user attached images that were dropped -> note them", d.noteImagesDropped)
    }

    @Test
    fun `no note when vision fell back but the user attached no images`() {
        val d = decideImageForwarding(
            modelImageCapable = true,
            visionEnabledPostLoad = false,
            userSentImages = false,
        )
        assertFalse(d.forwardImages)
        assertFalse("nothing to note when no images were attached", d.noteImagesDropped)
    }

    @Test
    fun `text-only model with a stray image attachment drops silently (no note)`() {
        // The user picked a model that never supported vision. Preserve the prior silent-drop
        // behaviour: no "vision unavailable on this device" note, because the device's vision
        // capability was never the issue here.
        val d = decideImageForwarding(
            modelImageCapable = false,
            visionEnabledPostLoad = false,
            userSentImages = true,
        )
        assertFalse(d.forwardImages)
        assertFalse("text-only model is not a device-vision failure", d.noteImagesDropped)
    }

    @Test
    fun `no images with vision live is a no-op`() {
        val d = decideImageForwarding(
            modelImageCapable = true,
            visionEnabledPostLoad = true,
            userSentImages = false,
        )
        assertTrue(d.forwardImages)
        assertFalse(d.noteImagesDropped)
    }
}
