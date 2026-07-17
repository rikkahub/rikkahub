package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import me.rerere.rikkahub.voiceagent.RetirementBarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioCaptureOwnershipTest {
    @Test
    fun `terminal result remains joinable until ownership publication hook completes`() {
        val routeFailure = IllegalStateException("route retirement failed")
        val resultPublished = CountDownLatch(1)
        val allowOwnershipRelease = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val ownerResult = AtomicReference<Throwable?>()
        val stopResult = AtomicReference<Throwable?>()
        val releaseResult = AtomicReference<Throwable?>()
        val ownership = fakeOwnership(
            onRetirementResultPublished = { published ->
                assertSame(routeFailure, published.exceptionOrNull())
                resultPublished.countDown()
                allowOwnershipRelease.await(5, TimeUnit.SECONDS)
            },
        )
        val lease = FakeCaptureRouteLease { throw routeFailure }
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)
        val owner = thread(name = "capture-result-publication-owner") {
            ownerResult.set(runCatching { ownership.terminate(token, recorder) }.exceptionOrNull())
        }
        assertTrue(resultPublished.await(5, TimeUnit.SECONDS))
        val stop = thread(name = "capture-result-publication-stop") {
            stopResult.set(runCatching { ownership.stop() }.exceptionOrNull())
            stopReturned.countDown()
        }
        val release = thread(name = "capture-result-publication-release") {
            releaseResult.set(runCatching { ownership.release() }.exceptionOrNull())
            releaseReturned.countDown()
        }

        try {
            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
            assertFalse(releaseReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowOwnershipRelease.countDown()
            owner.join(5_000)
            stop.join(5_000)
            release.join(5_000)
        }

        assertFalse(owner.isAlive)
        assertFalse(stop.isAlive)
        assertFalse(release.isAlive)
        assertSame(routeFailure, ownerResult.get())
        assertSame(routeFailure, stopResult.get())
        assertSame(routeFailure, releaseResult.get())
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `publication rejection runs every cleanup stage and preserves ordered failures`() {
        val cancelFailure = IllegalStateException("cancel failed")
        val releaseFailure = IllegalArgumentException("release failed")
        val routeFailure = UnsupportedOperationException("route failed")
        val ownership = fakeOwnership()
        val rejectedLease = FakeCaptureRouteLease { throw routeFailure }
        val rejectedToken = ownership.begin(rejectedLease)
        val currentLease = FakeCaptureRouteLease()
        val currentToken = ownership.begin(currentLease)
        val recorder = FakeCaptureRecorder(releaseFailure = releaseFailure)
        val task = FakeCaptureTask(onCancel = { throw cancelFailure })

        val thrown = runCatching {
            ownership.publishAndStart(rejectedToken, recorder, task)
        }.exceptionOrNull()

        assertSame(cancelFailure, thrown)
        assertEquals(listOf(releaseFailure, routeFailure), cancelFailure.suppressed.toList())
        assertEquals(1, task.cancelCalls)
        assertEquals(0, task.startCalls)
        assertEquals(0, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, rejectedLease.retireCalls)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.isCurrentLease(currentToken, currentLease))
    }

    @Test
    fun `publication rejection retires acquired lease once`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.begin(lease)
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        ownership.stop()

        val outcome = ownership.publishAndStart(token, recorder, task)

        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome)
        assertEquals(1, task.cancelCalls)
        assertEquals(0, task.startCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `start exception retires exact acquired lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.begin(lease)
        val recorder = FakeCaptureRecorder(startFailure = IllegalStateException("start failed"))
        val task = FakeCaptureTask()

        val thrown = runCatching { ownership.publishAndStart(token, recorder, task) }.exceptionOrNull()

        assertEquals("AudioRecord start failed", thrown?.message)
        assertEquals(1, task.cancelCalls)
        assertEquals(0, task.startCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `non-recording start retires exact acquired lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.begin(lease)
        val recorder = FakeCaptureRecorder(recordingAfterStart = false)
        val task = FakeCaptureTask()

        val thrown = runCatching { ownership.publishAndStart(token, recorder, task) }.exceptionOrNull()

        assertEquals("AudioRecord start failed", thrown?.message)
        assertEquals(1, task.cancelCalls)
        assertEquals(0, task.startCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `stale after start retires only stale acquired lease`() {
        lateinit var ownership: VoiceAudioCaptureOwnership<FakeCaptureRecorder, FakeCaptureTask>
        val staleLease = FakeCaptureRouteLease()
        val currentLease = FakeCaptureRouteLease()
        val staleRecorder = FakeCaptureRecorder(onStart = { ownership.stop() })
        val staleTask = FakeCaptureTask()
        ownership = fakeOwnership()
        val staleToken = ownership.begin(staleLease)

        val outcome = ownership.publishAndStart(staleToken, staleRecorder, staleTask)
        val currentToken = ownership.begin(currentLease)

        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome)
        assertEquals(0, staleTask.startCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.isCurrentLease(currentToken, currentLease))
    }

    @Test
    fun `explicit stop and release converge on exact lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        assertEquals(VoiceAudioCaptureStartOutcome.Started, ownership.publishAndStart(token, recorder, task))
        assertEquals(1, task.startCalls)

        ownership.stop()
        ownership.release()

        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `release returns true once retires exactly once and rejects later begin`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)

        assertTrue(ownership.release())
        assertFalse(ownership.release())
        val rejectedLease = FakeCaptureRouteLease()
        val thrown = runCatching { ownership.begin(rejectedLease) }.exceptionOrNull()

        assertRetiredExactlyOnce(lease, recorder, task)
        assertEquals("Voice audio engine is released", thrown?.message)
        assertEquals(0, rejectedLease.retireCalls)
    }

    @Test
    fun `autonomous termination retains completion boundary for concurrent release`() {
        val routeRetirementEntered = CountDownLatch(1)
        val allowRouteRetirement = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val closeBoundaryCrossed = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            routeRetirementEntered.countDown()
            allowRouteRetirement.await(5, TimeUnit.SECONDS)
        }
        val ownership = fakeOwnership()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)
        val autonomous = thread(name = "autonomous-capture-termination") {
            ownership.terminate(token, recorder)
        }
        assertTrue(routeRetirementEntered.await(5, TimeUnit.SECONDS))
        val release = thread(name = "capture-release") {
            ownership.release()
            releaseReturned.countDown()
            closeBoundaryCrossed.countDown()
        }

        try {
            assertFalse(releaseReturned.await(100, TimeUnit.MILLISECONDS))
            assertFalse(closeBoundaryCrossed.await(0, TimeUnit.MILLISECONDS))
        } finally {
            allowRouteRetirement.countDown()
            autonomous.join(5_000)
            release.join(5_000)
        }

        assertFalse(autonomous.isAlive)
        assertFalse(release.isAlive)
        assertTrue(closeBoundaryCrossed.await(0, TimeUnit.MILLISECONDS))
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `terminate first makes concurrent stop join exact retirement`() {
        val routeRetirementEntered = CountDownLatch(1)
        val allowRouteRetirement = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val staleLease = FakeCaptureRouteLease {
            routeRetirementEntered.countDown()
            allowRouteRetirement.await(5, TimeUnit.SECONDS)
        }
        val ownership = fakeOwnership()
        val staleRecorder = FakeCaptureRecorder()
        val staleTask = FakeCaptureTask()
        val staleToken = ownership.begin(staleLease)
        ownership.publishAndStart(staleToken, staleRecorder, staleTask)
        val autonomous = thread(name = "autonomous-capture-termination") {
            ownership.terminate(staleToken, staleRecorder)
        }
        assertTrue(routeRetirementEntered.await(5, TimeUnit.SECONDS))
        val stop = thread(name = "capture-stop") {
            ownership.stop()
            stopReturned.countDown()
        }

        try {
            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowRouteRetirement.countDown()
            autonomous.join(5_000)
            stop.join(5_000)
        }

        assertFalse(autonomous.isAlive)
        assertFalse(stop.isAlive)
        assertTrue(stopReturned.await(0, TimeUnit.MILLISECONDS))
        val currentLease = FakeCaptureRouteLease()
        val currentToken = ownership.begin(currentLease)
        assertRetiredExactlyOnce(staleLease, staleRecorder, staleTask)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.isCurrentLease(currentToken, currentLease))
    }

    @Test
    fun `stop first makes stale terminate replay exact retirement without touching newer lease`() {
        val ownership = fakeOwnership()
        val staleLease = FakeCaptureRouteLease()
        val staleRecorder = FakeCaptureRecorder()
        val staleTask = FakeCaptureTask()
        val staleToken = ownership.begin(staleLease)
        ownership.publishAndStart(staleToken, staleRecorder, staleTask)

        ownership.stop()
        val currentLease = FakeCaptureRouteLease()
        val currentToken = ownership.begin(currentLease)
        val cleared = ownership.terminate(staleToken, staleRecorder)

        assertFalse(cleared)
        assertRetiredExactlyOnce(staleLease, staleRecorder, staleTask)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.isCurrentLease(currentToken, currentLease))
    }

    @Test
    fun `task cancellation reentry keeps stop and release behind outer route teardown`() {
        lateinit var ownership: VoiceAudioCaptureOwnership<FakeCaptureRecorder, FakeCaptureTask>
        lateinit var token: VoiceAudioCaptureToken
        val routeRetirementEntered = CountDownLatch(1)
        val allowRouteRetirement = CountDownLatch(1)
        val competingStopReturned = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            routeRetirementEntered.countDown()
            allowRouteRetirement.await(5, TimeUnit.SECONDS)
        }
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask(onCancel = { ownership.terminate(token, recorder) })
        ownership = fakeOwnership()
        token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)
        val owner = thread(name = "reentrant-capture-retirement-owner") { ownership.stop() }
        assertTrue(routeRetirementEntered.await(5, TimeUnit.SECONDS))
        val competingStop = thread(name = "reentrant-capture-stop-waiter") {
            ownership.stop()
            competingStopReturned.countDown()
        }
        val release = thread(name = "reentrant-capture-release-waiter") {
            ownership.release()
            releaseReturned.countDown()
        }

        try {
            assertFalse(competingStopReturned.await(100, TimeUnit.MILLISECONDS))
            assertFalse(releaseReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowRouteRetirement.countDown()
            owner.join(5_000)
            competingStop.join(5_000)
            release.join(5_000)
        }

        assertFalse(owner.isAlive)
        assertFalse(competingStop.isAlive)
        assertFalse(release.isAlive)
        assertTrue(competingStopReturned.await(0, TimeUnit.MILLISECONDS))
        assertTrue(releaseReturned.await(0, TimeUnit.MILLISECONDS))
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `retirement runs every cleanup stage and replays ordered failure result`() {
        val cancelFailure = IllegalStateException("cancel failed")
        val stopFailure = IllegalArgumentException("stop failed")
        val releaseFailure = UnsupportedOperationException("release failed")
        val routeFailure = AssertionError("route failed")
        val cancelEntered = CountDownLatch(1)
        val allowCancelFailure = CountDownLatch(1)
        val waiterReturned = CountDownLatch(1)
        val ownerResult = AtomicReference<Throwable?>()
        val waiterResult = AtomicReference<Throwable?>()
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease { throw routeFailure }
        val recorder = FakeCaptureRecorder(
            stopFailure = stopFailure,
            releaseFailure = releaseFailure,
        )
        val task = FakeCaptureTask(
            onCancel = {
                cancelEntered.countDown()
                allowCancelFailure.await(5, TimeUnit.SECONDS)
                throw cancelFailure
            },
        )
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)
        val owner = thread(name = "failing-capture-retirement-owner") {
            ownerResult.set(runCatching { ownership.terminate(token, recorder) }.exceptionOrNull())
        }
        assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
        val waiter = thread(name = "failing-capture-retirement-waiter") {
            waiterResult.set(runCatching { ownership.stop() }.exceptionOrNull())
            waiterReturned.countDown()
        }

        try {
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowCancelFailure.countDown()
            owner.join(5_000)
            waiter.join(5_000)
        }
        val laterResult = runCatching { ownership.terminate(token, recorder) }.exceptionOrNull()

        assertFalse(owner.isAlive)
        assertFalse(waiter.isAlive)
        assertSame(cancelFailure, ownerResult.get())
        assertSame(cancelFailure, waiterResult.get())
        assertSame(cancelFailure, laterResult)
        assertEquals(listOf(stopFailure, releaseFailure, routeFailure), cancelFailure.suppressed.toList())
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    private fun assertRetiredExactlyOnce(
        lease: FakeCaptureRouteLease,
        recorder: FakeCaptureRecorder,
        task: FakeCaptureTask,
    ) {
        assertEquals(1, task.cancelCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, lease.retireCalls)
    }

    private fun fakeOwnership(
        onRetirementResultPublished: (Result<Unit>) -> Unit = {},
    ) = VoiceAudioCaptureOwnership(
        startRecorder = { recorder: FakeCaptureRecorder -> recorder.start() },
        isRecorderRecording = FakeCaptureRecorder::recording,
        stopRecorder = FakeCaptureRecorder::stop,
        releaseRecorder = FakeCaptureRecorder::release,
        startTask = FakeCaptureTask::start,
        cancelTask = FakeCaptureTask::cancel,
        onRetirementResultPublished = onRetirementResultPublished,
    )

    private class FakeCaptureRouteLease(
        private val onRetire: () -> Unit = {},
    ) : VoiceAudioCaptureRouteLease {
        private val retirement = RetirementBarrier()

        var retireCalls = 0
            private set

        override fun configureRecorder(recorder: AudioRecord) = Unit

        override fun retire() {
            retirement.retire {
                retireCalls += 1
                onRetire()
            }
        }
    }

    private class FakeCaptureRecorder(
        private val startFailure: RuntimeException? = null,
        private val recordingAfterStart: Boolean = true,
        private val onStart: () -> Unit = {},
        private val stopFailure: Throwable? = null,
        private val releaseFailure: Throwable? = null,
    ) {
        var stopCalls = 0
            private set
        var releaseCalls = 0
            private set

        fun start() {
            startFailure?.let { throw it }
            onStart()
        }

        fun recording(): Boolean = recordingAfterStart

        fun stop() {
            stopCalls += 1
            stopFailure?.let { throw it }
        }

        fun release() {
            releaseCalls += 1
            releaseFailure?.let { throw it }
        }
    }

    private class FakeCaptureTask(
        private val onCancel: () -> Unit = {},
    ) {
        var startCalls = 0
            private set
        var cancelCalls = 0
            private set

        fun start() {
            startCalls += 1
        }

        fun cancel() {
            cancelCalls += 1
            onCancel()
        }
    }
}
