package me.rerere.rikkahub.utils

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageUtilsTest {
    @Test
    fun `strict sample keeps images at or below the limit unchanged`() {
        assertEquals(1, ImageUtils.calculateStrictInSampleSize(3000, 2000, 4096))
        assertEquals(1, ImageUtils.calculateStrictInSampleSize(4096, 4096, 4096))
    }

    @Test
    fun `strict sample rounds up to a safe power of two`() {
        assertEquals(4, ImageUtils.calculateStrictInSampleSize(12000, 8000, 4096))
        assertEquals(8, ImageUtils.calculateStrictInSampleSize(24000, 1000, 4096))
    }

    @Test
    fun `strict sample uses ceiling at the maximum boundary`() {
        assertEquals(4, ImageUtils.calculateStrictInSampleSize(8193, 1000, 4096))
        assertEquals(4, ImageUtils.calculateStrictInSampleSize(1000, 8193, 4096))
    }

    @Test
    fun `strict sample tolerates unknown source bounds`() {
        assertEquals(1, ImageUtils.calculateStrictInSampleSize(-1, -1, 4096))
        assertEquals(1, ImageUtils.calculateStrictInSampleSize(0, 0, 4096))
    }

    @Test
    fun `strict sample rejects a nonpositive maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageUtils.calculateStrictInSampleSize(12000, 8000, 0)
        }
    }

    @Test
    fun `strict sample supports the largest representable power of two`() {
        assertEquals(
            1 shl 30,
            ImageUtils.calculateStrictInSampleSize(1 shl 30, 1, 1),
        )
    }

    @Test
    fun `strict sample rejects edges above the largest representable sample`() {
        assertThrows(IllegalStateException::class.java) {
            ImageUtils.calculateStrictInSampleSize((1 shl 30) + 1, 1, 1)
        }
        assertThrows(IllegalStateException::class.java) {
            ImageUtils.calculateStrictInSampleSize(Int.MAX_VALUE, 1, 1)
        }
    }

    @Test
    fun `crop preparation waits for the injected dispatcher`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var conversionRan = false
        val preparation = async(start = CoroutineStart.UNDISPATCHED) {
            ImageUtils.prepareImageForCrop(
                dispatcher = dispatcher,
                convert = {
                    conversionRan = true
                    true
                },
                copyOriginal = { fail("converted image must not be copied") },
            )
        }

        assertFalse(conversionRan)
        testScheduler.runCurrent()
        preparation.await()
        assertTrue(conversionRan)
    }

    @Test
    fun `successful conversion suppresses original copy`() = runTest {
        var copies = 0
        ImageUtils.prepareImageForCrop(
            dispatcher = StandardTestDispatcher(testScheduler),
            convert = { true },
            copyOriginal = { copies += 1 },
        )
        assertEquals(0, copies)
    }

    @Test
    fun `failed conversion copies original exactly once`() = runTest {
        var copies = 0
        ImageUtils.prepareImageForCrop(
            dispatcher = StandardTestDispatcher(testScheduler),
            convert = { false },
            copyOriginal = { copies += 1 },
        )
        assertEquals(1, copies)
    }

    @Test
    fun `copy failures propagate to the UI owner`() = runTest {
        val failure = IllegalStateException("copy failed")
        try {
            ImageUtils.prepareImageForCrop(
                dispatcher = StandardTestDispatcher(testScheduler),
                convert = { false },
                copyOriginal = { throw failure },
            )
            fail("IllegalStateException expected")
        } catch (thrown: IllegalStateException) {
            assertSame(failure, thrown.cause ?: thrown)
        }
    }

    @Test
    fun `conversion failures propagate to the UI owner`() = runTest {
        val failure = IllegalStateException("conversion failed")
        try {
            ImageUtils.prepareImageForCrop(
                dispatcher = StandardTestDispatcher(testScheduler),
                convert = { throw failure },
                copyOriginal = { fail("failed conversion must not be copied") },
            )
            fail("IllegalStateException expected")
        } catch (thrown: IllegalStateException) {
            assertSame(failure, thrown.cause ?: thrown)
        }
    }

    @Test
    fun `cancellation after conversion suppresses original copy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var copied = false
        lateinit var preparation: Deferred<Unit>
        preparation = async(dispatcher) {
            ImageUtils.prepareImageForCrop(
                dispatcher = dispatcher,
                convert = {
                    preparation.cancel()
                    false
                },
                copyOriginal = { copied = true },
            )
        }

        testScheduler.runCurrent()
        assertTrue(preparation.isCancelled)
        assertFalse(copied)
    }

    @Test
    fun `cancellation propagates without copying`() = runTest {
        var copied = false
        try {
            ImageUtils.prepareImageForCrop(
                dispatcher = StandardTestDispatcher(testScheduler),
                convert = { throw CancellationException("sheet dismissed") },
                copyOriginal = { copied = true },
            )
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected: cancellation belongs to the composition owner.
        }
        assertFalse(copied)
    }
}
