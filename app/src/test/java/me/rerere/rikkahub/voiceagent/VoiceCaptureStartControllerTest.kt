package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureStartControllerTest {
    @Test
    fun `launch rejects when session admission is closed`() = runTest {
        var startCaptureCalls = 0
        val controller = VoiceCaptureStartController(
            scope = this,
            lock = Any(),
            canStart = { false },
            canHandleFailure = { true },
            startCapture = { startCaptureCalls += 1 },
            onFailure = { _, _ -> error("unexpected capture failure") },
        )

        controller.launch(sessionId = 1L)

        assertEquals(0, startCaptureCalls)
        assertFalse(controller.hasOwnedJob())
    }

    @Test
    fun `launch rejects while exact failure handler owns controller`() = runTest {
        val failureHandlingEntered = CountDownLatch(1)
        val releaseFailureHandling = CountDownLatch(1)
        val startCaptureCalls = AtomicInteger()
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = VoiceCaptureStartController(
            scope = controllerScope,
            lock = Any(),
            canStart = { true },
            canHandleFailure = { true },
            startCapture = {
                if (startCaptureCalls.incrementAndGet() == 1) {
                    throw IllegalStateException("capture failed")
                }
            },
            onFailure = { _, _ ->
                failureHandlingEntered.countDown()
                releaseFailureHandling.await(5, TimeUnit.SECONDS)
            },
        )

        try {
            controller.launch(sessionId = 1L)
            assertTrue(failureHandlingEntered.await(5, TimeUnit.SECONDS))

            controller.launch(sessionId = 2L)

            assertEquals(1, startCaptureCalls.get())
            assertTrue(controller.hasOwnedJob())
            releaseFailureHandling.countDown()
            withTimeout(TEST_TIMEOUT_MS) {
                while (controller.hasOwnedJob()) delay(10)
            }
            assertEquals(1, startCaptureCalls.get())
            assertFalse(controller.hasOwnedJob())
        } finally {
            releaseFailureHandling.countDown()
            controllerScope.cancel()
        }
    }

    @Test
    fun `replacement cancels exact prior job without clearing current ownership`() = runTest {
        val starts = ArrayDeque<SuspendedControllerStart>()
        val controller = VoiceCaptureStartController(
            scope = this,
            lock = Any(),
            canStart = { true },
            canHandleFailure = { true },
            startCapture = {
                val start = starts.removeFirst()
                start.entered.complete(Unit)
                try {
                    start.release.await()
                } finally {
                    start.completed.complete(Unit)
                }
            },
            onFailure = { _, _ -> error("unexpected capture failure") },
        )
        val first = SuspendedControllerStart().also(starts::addLast)
        val second = SuspendedControllerStart().also(starts::addLast)

        controller.launch(sessionId = 1L)
        await(first.entered)
        controller.launch(sessionId = 1L)
        await(first.completed)
        await(second.entered)

        assertTrue(controller.hasOwnedJob())
        second.release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) {
            while (controller.hasOwnedJob()) delay(10)
        }
        assertFalse(controller.hasOwnedJob())
    }

    @Test
    fun `only exact current active job can claim background failure`() = runTest {
        var canHandleFailure = false
        val failures = mutableListOf<Pair<Long, Throwable>>()
        val staleFailure = IllegalStateException("stale")
        val currentFailure = IllegalArgumentException("current")
        val starts = ArrayDeque<suspend () -> Unit>().apply {
            addLast { throw staleFailure }
            addLast { throw currentFailure }
        }
        val controller = VoiceCaptureStartController(
            scope = this,
            lock = Any(),
            canStart = { true },
            canHandleFailure = { canHandleFailure },
            startCapture = { starts.removeFirst().invoke() },
            onFailure = { sessionId, failure -> failures += sessionId to failure },
        )

        controller.launch(sessionId = 1L)
        withTimeout(TEST_TIMEOUT_MS) {
            while (controller.hasOwnedJob()) delay(10)
        }
        canHandleFailure = true
        controller.launch(sessionId = 2L)
        withTimeout(TEST_TIMEOUT_MS) {
            while (failures.isEmpty()) delay(10)
        }

        assertEquals(listOf(2L to currentFailure), failures)
        assertFalse(controller.hasOwnedJob())
    }

    private suspend fun await(signal: Deferred<Unit>) {
        withTimeout(TEST_TIMEOUT_MS) { signal.await() }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private data class SuspendedControllerStart(
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
        val completed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private companion object {
        const val TEST_TIMEOUT_MS = 500L
    }
}
