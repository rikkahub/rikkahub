package me.rerere.rikkahub.ui.pages.chat

import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatImagePreparationTest {
    @Test
    fun `image preparation starts only while idle`() {
        assertTrue(canStartImagePreparation(isPreparingImage = false))
        assertFalse(canStartImagePreparation(isPreparingImage = true))
    }

    @Test
    fun `successful preparation launches prepared image and transfers ownership`() = runTest {
        val events = mutableListOf<String>()

        coordinateImagePreparation(
            prepare = { events += "prepare" },
            launchPrepared = { events += "prepared" },
            launchFallback = { fail("successful preparation must not fall back") },
            cleanupPartial = { events += "cleanup" },
            onFinished = { events += "finished" },
        )

        assertEquals(listOf("prepare", "prepared", "finished"), events)
    }

    @Test
    fun `ordinary preparation error falls back cleans partial and finishes`() = runTest {
        val failure = IllegalStateException("prepare failed")
        val events = mutableListOf<String>()

        coordinateImagePreparation(
            prepare = {
                events += "prepare"
                throw failure
            },
            launchPrepared = { fail("failed preparation must not launch prepared image") },
            launchFallback = { error ->
                assertSame(failure, error)
                events += "fallback"
            },
            cleanupPartial = { events += "cleanup" },
            onFinished = { events += "finished" },
        )

        assertEquals(listOf("prepare", "fallback", "cleanup", "finished"), events)
    }

    @Test
    fun `cancellation while preparation is suspended cleans and finishes without launch`() = runTest {
        val events = mutableListOf<String>()
        val preparation = async {
            coordinateImagePreparation(
                prepare = {
                    events += "prepare"
                    awaitCancellation()
                },
                launchPrepared = { events += "prepared" },
                launchFallback = { events += "fallback" },
                cleanupPartial = { events += "cleanup" },
                onFinished = { events += "finished" },
            )
        }

        testScheduler.runCurrent()
        preparation.cancel(CancellationException("sheet dismissed"))
        try {
            preparation.await()
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected: cancellation belongs to the composition owner.
        }

        assertEquals(listOf("prepare", "cleanup", "finished"), events)
    }
}
