package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import me.rerere.rikkahub.voiceagent.RetirementBarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioCaptureOwnershipTest {
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

        ownership.stop()
        ownership.release()

        assertRetiredExactlyOnce(lease, recorder, task)
    }

    @Test
    fun `release retires active exact lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)

        ownership.release()

        assertRetiredExactlyOnce(lease, recorder, task)
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
    fun `task cancellation reentry does not repeat or deadlock retirement`() {
        lateinit var ownership: VoiceAudioCaptureOwnership<FakeCaptureRecorder, FakeCaptureTask>
        lateinit var token: VoiceAudioCaptureToken
        val lease = FakeCaptureRouteLease()
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask(onCancel = { ownership.terminate(token, recorder) })
        ownership = fakeOwnership()
        token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)

        val stop = thread(name = "reentrant-capture-stop") { ownership.stop() }
        stop.join(5_000)

        assertFalse(stop.isAlive)
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

    private fun fakeOwnership() = VoiceAudioCaptureOwnership(
        startRecorder = { recorder: FakeCaptureRecorder -> recorder.start() },
        isRecorderRecording = FakeCaptureRecorder::recording,
        stopRecorder = FakeCaptureRecorder::stop,
        releaseRecorder = FakeCaptureRecorder::release,
        startTask = FakeCaptureTask::start,
        cancelTask = FakeCaptureTask::cancel,
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
        }

        fun release() {
            releaseCalls += 1
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
