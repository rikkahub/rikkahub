package me.rerere.ai.util

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #114: the base64-inline video/audio paths in [FileEncoder]
 * must reject an oversized source file BEFORE allocating its in-memory base64 string,
 * while leaving normal small media untouched.
 *
 * This pins the pure size invariant ([MediaEncodeLimits.checkMediaSizeWithinLimit]) that
 * the File-based encode paths delegate to. It runs under `testDebugUnitTest` (pure JVM,
 * no device/network); the File/android.util.Base64 paths themselves cannot.
 */
class MediaEncodeLimitsTest {

    @Test
    fun `size at or under the video cap is accepted`() {
        // No exception => accepted. Boundary (== cap) and a normal small file must pass,
        // so there is no regression for normal small media.
        MediaEncodeLimits.checkMediaSizeWithinLimit(
            sizeBytes = MediaEncodeLimits.MAX_VIDEO_BYTES,
            maxBytes = MediaEncodeLimits.MAX_VIDEO_BYTES,
            label = "video"
        )
        MediaEncodeLimits.checkMediaSizeWithinLimit(
            sizeBytes = 1L,
            maxBytes = MediaEncodeLimits.MAX_VIDEO_BYTES,
            label = "video"
        )
    }

    @Test
    fun `size at or under the audio cap is accepted`() {
        MediaEncodeLimits.checkMediaSizeWithinLimit(
            sizeBytes = MediaEncodeLimits.MAX_AUDIO_BYTES,
            maxBytes = MediaEncodeLimits.MAX_AUDIO_BYTES,
            label = "audio"
        )
        MediaEncodeLimits.checkMediaSizeWithinLimit(
            sizeBytes = 0L,
            maxBytes = MediaEncodeLimits.MAX_AUDIO_BYTES,
            label = "audio"
        )
    }

    @Test
    fun `video one byte over the cap is rejected before allocation`() {
        val ex = assertThrows(MediaTooLargeException::class.java) {
            MediaEncodeLimits.checkMediaSizeWithinLimit(
                sizeBytes = MediaEncodeLimits.MAX_VIDEO_BYTES + 1,
                maxBytes = MediaEncodeLimits.MAX_VIDEO_BYTES,
                label = "video"
            )
        }
        assertTrue("message names the media kind", ex.message!!.contains("video"))
        assertTrue("message names the MB limit", ex.message!!.contains("20 MB"))
    }

    @Test
    fun `audio one byte over the cap is rejected before allocation`() {
        val ex = assertThrows(MediaTooLargeException::class.java) {
            MediaEncodeLimits.checkMediaSizeWithinLimit(
                sizeBytes = MediaEncodeLimits.MAX_AUDIO_BYTES + 1,
                maxBytes = MediaEncodeLimits.MAX_AUDIO_BYTES,
                label = "audio"
            )
        }
        assertTrue("message names the media kind", ex.message!!.contains("audio"))
        assertTrue("message names the MB limit", ex.message!!.contains("20 MB"))
    }

    @Test
    fun `image one byte over the cap is rejected before allocation`() {
        // The GIF branch in compressAndEncode bypasses the dimension/pixel caps, so it relies on
        // this byte cap. Without it a large GIF would inflate fully in memory.
        val ex = assertThrows(MediaTooLargeException::class.java) {
            MediaEncodeLimits.checkMediaSizeWithinLimit(
                sizeBytes = MediaEncodeLimits.MAX_IMAGE_BYTES + 1,
                maxBytes = MediaEncodeLimits.MAX_IMAGE_BYTES,
                label = "image"
            )
        }
        assertTrue("message names the media kind", ex.message!!.contains("image"))
        assertTrue("message names the MB limit", ex.message!!.contains("20 MB"))
    }

    @Test
    fun `rethrowIfMediaTooLarge surfaces the rejection instead of letting getOrNull drop it`() {
        // Regression for the swallowed-error path: a provider consumes
        // encodeBase64(...).getOrNull(), so a MediaTooLargeException left inside the Result
        // would silently omit the oversized media and send a request with no error.
        // rethrowIfMediaTooLarge() must re-surface it so getOrNull() never sees it.
        val failed: Result<String> = runCatching {
            MediaEncodeLimits.checkMediaSizeWithinLimit(
                sizeBytes = MediaEncodeLimits.MAX_VIDEO_BYTES + 1,
                maxBytes = MediaEncodeLimits.MAX_VIDEO_BYTES,
                label = "video"
            )
            "unreachable"
        }
        assertThrows(MediaTooLargeException::class.java) {
            failed.rethrowIfMediaTooLarge()
        }
    }

    @Test
    fun `rethrowIfMediaTooLarge leaves non-size failures in the Result`() {
        // Other encode failures (e.g. missing file) keep the pre-existing getOrNull() behavior.
        val other: Result<String> = runCatching { throw IllegalStateException("missing file") }
        // Does not throw; the failure stays in the Result for the caller's getOrNull() to drop.
        val passthrough = other.rethrowIfMediaTooLarge()
        assertTrue("non-size failure stays a failure", passthrough.isFailure)
    }
}
