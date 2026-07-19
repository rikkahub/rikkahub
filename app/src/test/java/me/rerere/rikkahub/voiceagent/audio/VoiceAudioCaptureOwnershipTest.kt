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
        val stopAttemptingRetirement = CountDownLatch(1)
        val releaseAttemptingRetirement = CountDownLatch(1)
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
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        ownership.publishAndStart(token, recorder, task)
        val owner = thread(name = "capture-result-publication-owner") {
            ownerResult.set(runCatching { ownership.terminate(token, recorder) }.exceptionOrNull())
        }
        assertTrue(resultPublished.await(5, TimeUnit.SECONDS))
        val stop = thread(name = "capture-result-publication-stop") {
            stopAttemptingRetirement.countDown()
            stopResult.set(runCatching { ownership.stop() }.exceptionOrNull())
            stopReturned.countDown()
        }
        val release = thread(name = "capture-result-publication-release") {
            releaseAttemptingRetirement.countDown()
            releaseResult.set(runCatching { ownership.release() }.exceptionOrNull())
            releaseReturned.countDown()
        }

        try {
            assertTrue(stopAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(releaseAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(stop, Thread.State.BLOCKED))
            assertTrue(awaitThreadState(release, Thread.State.BLOCKED))
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
        val rejectedToken = ownership.reserve()
        assertTrue(ownership.publishRoute(rejectedToken, rejectedLease))
        assertSame(routeFailure, runCatching { ownership.stop() }.exceptionOrNull())
        val currentLease = FakeCaptureRouteLease()
        val currentToken = ownership.reserve()
        assertTrue(ownership.publishRoute(currentToken, currentLease))
        val currentRecorder = FakeCaptureRecorder()
        val currentTask = FakeCaptureTask()
        assertEquals(
            VoiceAudioCaptureStartOutcome.Started,
            ownership.publishAndStart(currentToken, currentRecorder, currentTask),
        )
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
        assertTrue(ownership.isCurrent(currentToken, currentRecorder))
    }

    @Test
    fun `publication rejection retires acquired lease once`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
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
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
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
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
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
        val staleToken = ownership.reserve()
        assertTrue(ownership.publishRoute(staleToken, staleLease))

        val outcome = ownership.publishAndStart(staleToken, staleRecorder, staleTask)
        val currentToken = ownership.reserve()
        assertTrue(ownership.publishRoute(currentToken, currentLease))

        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome)
        assertEquals(0, staleTask.startCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.abort(currentToken))
        assertEquals(1, currentLease.retireCalls)
    }

    @Test
    fun `stop completing before active publication rejects start task and permits next reserve`() {
        val allowRecorderStart = CountDownLatch(1)
        val recorderStartEntered = CountDownLatch(1)
        val activePublicationEntered = CountDownLatch(1)
        val allowActivePublication = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val stopFailure = AtomicReference<Throwable?>()
        val outcome = AtomicReference<VoiceAudioCaptureStartOutcome?>()
        val events = mutableListOf<String>()
        val ownership = fakeOwnership(
            beforeActivePublication = {
                activePublicationEntered.countDown()
                allowActivePublication.await(5, TimeUnit.SECONDS)
            },
        )
        val lease = FakeCaptureRouteLease { events += "route" }
        val recorder = FakeCaptureRecorder(
            onStart = {
                recorderStartEntered.countDown()
                allowRecorderStart.await(5, TimeUnit.SECONDS)
            },
            onStop = { events += "stop" },
            onRelease = { events += "release" },
        )
        val task = FakeCaptureTask(onCancel = { events += "cancel" })
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        val activation = thread(name = "capture-activation-owner") {
            outcome.set(ownership.publishAndStart(token, recorder, task))
        }
        assertTrue(recorderStartEntered.await(5, TimeUnit.SECONDS))
        val stop = thread(name = "capture-stop-overtaking-activation") {
            stopFailure.set(runCatching { ownership.stop() }.exceptionOrNull())
            stopReturned.countDown()
        }

        try {
            assertTrue(awaitThreadState(stop, Thread.State.WAITING))
            allowRecorderStart.countDown()
            assertTrue(activePublicationEntered.await(5, TimeUnit.SECONDS))
            assertTrue(stopReturned.await(5, TimeUnit.SECONDS))
            assertSame(null, stopFailure.get())
            assertEquals(listOf("cancel", "stop", "release", "route"), events)
            assertEquals(0, task.startCalls)

            val nextToken = ownership.reserve()
            assertTrue(ownership.abort(nextToken))
        } finally {
            allowRecorderStart.countDown()
            allowActivePublication.countDown()
            activation.join(5_000)
            stop.join(5_000)
        }

        assertFalse(activation.isAlive)
        assertFalse(stop.isAlive)
        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome.get())
        assertEquals(0, task.startCalls)
        assertEquals(listOf("cancel", "stop", "release", "route"), events)
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `release completing before active publication rejects start task permanently`() {
        val allowRecorderStart = CountDownLatch(1)
        val recorderStartEntered = CountDownLatch(1)
        val activePublicationEntered = CountDownLatch(1)
        val allowActivePublication = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val releaseResult = AtomicReference<Boolean?>()
        val releaseFailure = AtomicReference<Throwable?>()
        val outcome = AtomicReference<VoiceAudioCaptureStartOutcome?>()
        val events = mutableListOf<String>()
        val ownership = fakeOwnership(
            beforeActivePublication = {
                activePublicationEntered.countDown()
                allowActivePublication.await(5, TimeUnit.SECONDS)
            },
        )
        val lease = FakeCaptureRouteLease { events += "route" }
        val recorder = FakeCaptureRecorder(
            onStart = {
                recorderStartEntered.countDown()
                allowRecorderStart.await(5, TimeUnit.SECONDS)
            },
            onStop = { events += "stop" },
            onRelease = { events += "release" },
        )
        val task = FakeCaptureTask(onCancel = { events += "cancel" })
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        val activation = thread(name = "capture-activation-owner") {
            outcome.set(ownership.publishAndStart(token, recorder, task))
        }
        assertTrue(recorderStartEntered.await(5, TimeUnit.SECONDS))
        val release = thread(name = "capture-release-overtaking-activation") {
            val attempt = runCatching { ownership.release() }
            releaseResult.set(attempt.getOrNull())
            releaseFailure.set(attempt.exceptionOrNull())
            releaseReturned.countDown()
        }

        try {
            assertTrue(awaitThreadState(release, Thread.State.WAITING))
            allowRecorderStart.countDown()
            assertTrue(activePublicationEntered.await(5, TimeUnit.SECONDS))
            assertTrue(releaseReturned.await(5, TimeUnit.SECONDS))
            assertSame(null, releaseFailure.get())
            assertEquals(true, releaseResult.get())
            assertEquals(listOf("cancel", "stop", "release", "route"), events)
            assertEquals(0, task.startCalls)
        } finally {
            allowRecorderStart.countDown()
            allowActivePublication.countDown()
            activation.join(5_000)
            release.join(5_000)
        }

        assertFalse(activation.isAlive)
        assertFalse(release.isAlive)
        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome.get())
        assertEquals(0, task.startCalls)
        assertEquals(listOf("cancel", "stop", "release", "route"), events)
        assertRetiredExactlyOnce(lease, recorder, task)
        assertEquals(
            "Voice audio engine is released",
            runCatching { ownership.reserve() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `explicit stop and release converge on exact lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
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
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        ownership.publishAndStart(token, recorder, task)

        assertTrue(ownership.release())
        assertFalse(ownership.release())
        val rejectedLease = FakeCaptureRouteLease()
        val thrown = runCatching { ownership.reserve() }.exceptionOrNull()

        assertRetiredExactlyOnce(lease, recorder, task)
        assertEquals("Voice audio engine is released", thrown?.message)
        assertEquals(0, rejectedLease.retireCalls)
    }

    @Test
    fun `autonomous termination retains completion boundary for concurrent release`() {
        val routeRetirementEntered = CountDownLatch(1)
        val allowRouteRetirement = CountDownLatch(1)
        val releaseAttemptingRetirement = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val closeBoundaryCrossed = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            routeRetirementEntered.countDown()
            allowRouteRetirement.await(5, TimeUnit.SECONDS)
        }
        val ownership = fakeOwnership()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        ownership.publishAndStart(token, recorder, task)
        val autonomous = thread(name = "autonomous-capture-termination") {
            ownership.terminate(token, recorder)
        }
        assertTrue(routeRetirementEntered.await(5, TimeUnit.SECONDS))
        val release = thread(name = "capture-release") {
            releaseAttemptingRetirement.countDown()
            ownership.release()
            releaseReturned.countDown()
            closeBoundaryCrossed.countDown()
        }

        try {
            assertTrue(releaseAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(release, Thread.State.WAITING))
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
        assertEquals(
            "Voice audio engine is released",
            runCatching { ownership.reserve() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `terminate first makes concurrent stop join exact retirement`() {
        val routeRetirementEntered = CountDownLatch(1)
        val allowRouteRetirement = CountDownLatch(1)
        val stopAttemptingRetirement = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val staleLease = FakeCaptureRouteLease {
            routeRetirementEntered.countDown()
            allowRouteRetirement.await(5, TimeUnit.SECONDS)
        }
        val ownership = fakeOwnership()
        val staleRecorder = FakeCaptureRecorder()
        val staleTask = FakeCaptureTask()
        val staleToken = ownership.reserve()
        assertTrue(ownership.publishRoute(staleToken, staleLease))
        ownership.publishAndStart(staleToken, staleRecorder, staleTask)
        val autonomous = thread(name = "autonomous-capture-termination") {
            ownership.terminate(staleToken, staleRecorder)
        }
        assertTrue(routeRetirementEntered.await(5, TimeUnit.SECONDS))
        val stop = thread(name = "capture-stop") {
            stopAttemptingRetirement.countDown()
            ownership.stop()
            stopReturned.countDown()
        }

        try {
            assertTrue(stopAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(stop, Thread.State.WAITING))
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
        val currentToken = ownership.reserve()
        assertTrue(ownership.publishRoute(currentToken, currentLease))
        assertRetiredExactlyOnce(staleLease, staleRecorder, staleTask)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.abort(currentToken))
        assertEquals(1, currentLease.retireCalls)
    }

    @Test
    fun `stop first makes stale terminate replay exact retirement without touching newer lease`() {
        val ownership = fakeOwnership()
        val staleLease = FakeCaptureRouteLease()
        val staleRecorder = FakeCaptureRecorder()
        val staleTask = FakeCaptureTask()
        val staleToken = ownership.reserve()
        assertTrue(ownership.publishRoute(staleToken, staleLease))
        ownership.publishAndStart(staleToken, staleRecorder, staleTask)

        ownership.stop()
        val currentLease = FakeCaptureRouteLease()
        val currentToken = ownership.reserve()
        assertTrue(ownership.publishRoute(currentToken, currentLease))
        val cleared = ownership.terminate(staleToken, staleRecorder)

        assertFalse(cleared)
        assertRetiredExactlyOnce(staleLease, staleRecorder, staleTask)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.abort(currentToken))
        assertEquals(1, currentLease.retireCalls)
    }

    @Test
    fun `stale abort replays exact retirement failure without touching newer lease`() {
        val routeFailure = IllegalStateException("route failed")
        val ownership = fakeOwnership()
        val staleLease = FakeCaptureRouteLease { throw routeFailure }
        val staleToken = ownership.reserve()
        assertTrue(ownership.publishRoute(staleToken, staleLease))
        assertSame(routeFailure, runCatching { ownership.stop() }.exceptionOrNull())
        val currentLease = FakeCaptureRouteLease()
        val currentToken = ownership.reserve()
        assertTrue(ownership.publishRoute(currentToken, currentLease))

        val replayed = runCatching { ownership.abort(staleToken) }.exceptionOrNull()

        assertSame(routeFailure, replayed)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.abort(currentToken))
        assertEquals(1, currentLease.retireCalls)
    }

    @Test
    fun `task cancellation reentry keeps stop and release behind outer route teardown`() {
        lateinit var ownership: VoiceAudioCaptureOwnership<FakeCaptureRecorder, FakeCaptureTask>
        lateinit var token: VoiceAudioCaptureToken
        val routeRetirementEntered = CountDownLatch(1)
        val allowRouteRetirement = CountDownLatch(1)
        val competingStopAttemptingRetirement = CountDownLatch(1)
        val releaseAttemptingRetirement = CountDownLatch(1)
        val competingStopReturned = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            routeRetirementEntered.countDown()
            allowRouteRetirement.await(5, TimeUnit.SECONDS)
        }
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask(onCancel = { ownership.terminate(token, recorder) })
        ownership = fakeOwnership()
        token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        ownership.publishAndStart(token, recorder, task)
        val owner = thread(name = "reentrant-capture-retirement-owner") { ownership.stop() }
        assertTrue(routeRetirementEntered.await(5, TimeUnit.SECONDS))
        val competingStop = thread(name = "reentrant-capture-stop-waiter") {
            competingStopAttemptingRetirement.countDown()
            ownership.stop()
            competingStopReturned.countDown()
        }
        val release = thread(name = "reentrant-capture-release-waiter") {
            releaseAttemptingRetirement.countDown()
            ownership.release()
            releaseReturned.countDown()
        }

        try {
            assertTrue(competingStopAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(releaseAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(competingStop, Thread.State.WAITING))
            assertTrue(awaitThreadState(release, Thread.State.WAITING))
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
        val waiterAttemptingRetirement = CountDownLatch(1)
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
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        ownership.publishAndStart(token, recorder, task)
        val owner = thread(name = "failing-capture-retirement-owner") {
            ownerResult.set(runCatching { ownership.terminate(token, recorder) }.exceptionOrNull())
        }
        assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
        val waiter = thread(name = "failing-capture-retirement-waiter") {
            waiterAttemptingRetirement.countDown()
            waiterResult.set(runCatching { ownership.stop() }.exceptionOrNull())
            waiterReturned.countDown()
        }

        try {
            assertTrue(waiterAttemptingRetirement.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(waiter, Thread.State.WAITING))
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

    @Test
    fun `stop during reserved rejects late route and retires it locally`() {
        val ownership = fakeOwnership()
        val token = ownership.reserve()
        ownership.stop()
        val lease = FakeCaptureRouteLease()

        assertFalse(ownership.publishRoute(token, lease))
        assertEquals(0, lease.retireCalls)
        lease.retire()
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `release during reserved rejects late route and permanently rejects reserve`() {
        val ownership = fakeOwnership()
        val token = ownership.reserve()
        assertTrue(ownership.release())
        val lease = FakeCaptureRouteLease()

        assertFalse(ownership.publishRoute(token, lease))
        assertEquals(0, lease.retireCalls)
        assertEquals(
            "Voice audio engine is released",
            runCatching { ownership.reserve() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `stop during routed retires lease and permits another reservation`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))

        ownership.stop()

        assertEquals(1, lease.retireCalls)
        val nextToken = ownership.reserve()
        assertTrue(ownership.abort(nextToken))
    }

    @Test
    fun `release during routed retires lease and permanently rejects reserve`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))

        assertTrue(ownership.release())

        assertEquals(1, lease.retireCalls)
        assertEquals(
            "Voice audio engine is released",
            runCatching { ownership.reserve() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `terminate during routed does not consume later route retirement`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))

        assertFalse(ownership.terminate(token, FakeCaptureRecorder()))
        assertTrue(ownership.abort(token))

        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `rejected lazy task start retires admitted capture once`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask(startResult = false)
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))

        val outcome = ownership.publishAndStart(token, recorder, task)

        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome)
        assertEquals(1, task.startCalls)
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `stop during activation waits then cleans in exact order`() {
        assertActivationRetirement(release = false)
    }

    @Test
    fun `release during activation waits then cleans in exact order and remains released`() {
        assertActivationRetirement(release = true)
    }

    @Test
    fun `reentrant stop during activation defers retirement to activation owner`() {
        lateinit var ownership: VoiceAudioCaptureOwnership<FakeCaptureRecorder, FakeCaptureTask>
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "retire-route" }
        val task = FakeCaptureTask(onCancel = { events += "cancel-task" })
        val recorder = FakeCaptureRecorder(
            onStart = { ownership.stop() },
            onStop = { events += "stop-recorder" },
            onRelease = { events += "release-recorder" },
        )
        ownership = fakeOwnership()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))

        val outcome = ownership.publishAndStart(token, recorder, task)

        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome)
        assertEquals(
            listOf("cancel-task", "stop-recorder", "release-recorder", "retire-route"),
            events,
        )
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `interrupted activation waiter restores interrupt after exact retirement`() {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val waiterReturned = CountDownLatch(1)
        val interruptedOnReturn = AtomicReference(false)
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder(
            onStart = {
                startEntered.countDown()
                allowStart.await(5, TimeUnit.SECONDS)
            },
        )
        val task = FakeCaptureTask()
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        val activation = thread(name = "interrupted-activation-owner") {
            ownership.publishAndStart(token, recorder, task)
        }
        assertTrue(startEntered.await(5, TimeUnit.SECONDS))
        val waiter = thread(name = "interrupted-activation-waiter") {
            Thread.currentThread().interrupt()
            ownership.stop()
            interruptedOnReturn.set(Thread.currentThread().isInterrupted)
            waiterReturned.countDown()
        }

        try {
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowStart.countDown()
            activation.join(5_000)
            waiter.join(5_000)
        }

        assertFalse(activation.isAlive)
        assertFalse(waiter.isAlive)
        assertTrue(interruptedOnReturn.get())
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `thrown task start stays primary and exact cleanup failure replays`() {
        val startFailure = IllegalStateException("task start failed")
        val cancelFailure = IllegalArgumentException("task cancel failed")
        val stopFailure = UnsupportedOperationException("recorder stop failed")
        val releaseFailure = AssertionError("recorder release failed")
        val routeFailure = RuntimeException("route retirement failed")
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease { throw routeFailure }
        val recorder = FakeCaptureRecorder(
            stopFailure = stopFailure,
            releaseFailure = releaseFailure,
        )
        val task = FakeCaptureTask(
            onCancel = { throw cancelFailure },
            startFailure = startFailure,
        )
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))

        val thrown = runCatching {
            ownership.publishAndStart(token, recorder, task)
        }.exceptionOrNull()
        val replayed = runCatching { ownership.terminate(token, recorder) }.exceptionOrNull()

        assertSame(startFailure, thrown)
        assertEquals(listOf(cancelFailure), startFailure.suppressed.toList())
        assertSame(cancelFailure, replayed)
        assertEquals(listOf(stopFailure, releaseFailure, routeFailure), cancelFailure.suppressed.toList())
        assertRetiredExactlyOnce(lease, recorder, task)
    }

    private fun assertActivationRetirement(release: Boolean) {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val retirementAttempted = CountDownLatch(1)
        val events = mutableListOf<String>()
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease { events += "retire-route" }
        val recorder = FakeCaptureRecorder(
            onStart = {
                startEntered.countDown()
                allowStart.await(5, TimeUnit.SECONDS)
            },
            onStop = { events += "stop-recorder" },
            onRelease = { events += "release-recorder" },
        )
        val task = FakeCaptureTask(onCancel = { events += "cancel-task" })
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        val outcome = AtomicReference<VoiceAudioCaptureStartOutcome>()
        val activation = thread(name = "capture-activation-owner") {
            outcome.set(ownership.publishAndStart(token, recorder, task))
        }
        assertTrue(startEntered.await(5, TimeUnit.SECONDS))
        val retirement = thread(name = "capture-activation-retirement") {
            retirementAttempted.countDown()
            if (release) ownership.release() else ownership.stop()
        }

        try {
            assertTrue(retirementAttempted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(retirement, Thread.State.WAITING))
            assertEquals(0, task.cancelCalls)
            assertEquals(0, recorder.stopCalls)
            assertEquals(0, recorder.releaseCalls)
            assertEquals(0, lease.retireCalls)
        } finally {
            allowStart.countDown()
            activation.join(5_000)
            retirement.join(5_000)
        }

        assertFalse(activation.isAlive)
        assertFalse(retirement.isAlive)
        assertEquals(VoiceAudioCaptureStartOutcome.Rejected, outcome.get())
        assertEquals(
            listOf("cancel-task", "stop-recorder", "release-recorder", "retire-route"),
            events,
        )
        assertRetiredExactlyOnce(lease, recorder, task)
        if (release) {
            assertEquals(
                "Voice audio engine is released",
                runCatching { ownership.reserve() }.exceptionOrNull()?.message,
            )
        }
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
        beforeActivePublication: () -> Unit = {},
    ) = VoiceAudioCaptureOwnership(
        startRecorder = { recorder: FakeCaptureRecorder -> recorder.start() },
        isRecorderRecording = FakeCaptureRecorder::recording,
        stopRecorder = FakeCaptureRecorder::stop,
        releaseRecorder = FakeCaptureRecorder::release,
        startTask = FakeCaptureTask::start,
        cancelTask = FakeCaptureTask::cancel,
        onRetirementResultPublished = onRetirementResultPublished,
        beforeActivePublication = beforeActivePublication,
    )

    private class FakeCaptureRouteLease(
        private val onRetire: () -> Unit = {},
    ) : VoiceAudioCaptureRouteLease {
        private val retirement = RetirementBarrier()

        var retireCalls = 0
            private set

        override suspend fun prepare() = Unit

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
        private val onStop: () -> Unit = {},
        private val onRelease: () -> Unit = {},
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
            onStop()
            stopFailure?.let { throw it }
        }

        fun release() {
            releaseCalls += 1
            onRelease()
            releaseFailure?.let { throw it }
        }
    }

    private class FakeCaptureTask(
        private val onCancel: () -> Unit = {},
        private val startResult: Boolean = true,
        private val startFailure: Throwable? = null,
    ) {
        var startCalls = 0
            private set
        var cancelCalls = 0
            private set

        fun start(): Boolean {
            startCalls += 1
            startFailure?.let { throw it }
            return startResult
        }

        fun cancel() {
            cancelCalls += 1
            onCancel()
        }
    }
}

private fun awaitThreadState(
    thread: Thread,
    expectedState: Thread.State,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (System.nanoTime() < deadlineNanos) {
        if (thread.state == expectedState) return true
        Thread.yield()
    }
    return thread.state == expectedState
}
