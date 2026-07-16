package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRouteControllerTest {
    @Test
    fun `engine ownership rejects publication and retires acquired lease once`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease {}
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
    fun `engine ownership start exception retires exact acquired lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease {}
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
    fun `engine ownership non-recording start retires exact acquired lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease {}
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
    fun `engine ownership stale after start retires only stale acquired lease`() {
        lateinit var ownership: VoiceAudioCaptureOwnership<FakeCaptureRecorder, FakeCaptureTask>
        val staleLease = FakeCaptureRouteLease {}
        val currentLease = FakeCaptureRouteLease {}
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
    fun `engine ownership explicit stop and release converge on exact lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease {}
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        assertEquals(VoiceAudioCaptureStartOutcome.Started, ownership.publishAndStart(token, recorder, task))

        ownership.stop()
        ownership.release()

        assertEquals(1, lease.retireCalls)
        assertEquals(1, task.cancelCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
    }

    @Test
    fun `engine ownership release retires active exact lease`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease {}
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)

        ownership.release()

        assertEquals(1, lease.retireCalls)
        assertEquals(1, task.cancelCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
    }

    @Test
    fun `engine autonomous termination and later stop release retire exact lease once`() {
        val ownership = fakeOwnership()
        val lease = FakeCaptureRouteLease {}
        val recorder = FakeCaptureRecorder()
        val task = FakeCaptureTask()
        val token = ownership.begin(lease)
        ownership.publishAndStart(token, recorder, task)

        ownership.terminate(token, recorder)
        ownership.stop()
        ownership.release()

        assertEquals(1, lease.retireCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
    }

    @Test
    fun `stale autonomous cleanup never retires newer engine lease`() {
        val ownership = fakeOwnership()
        val staleLease = FakeCaptureRouteLease {}
        val staleRecorder = FakeCaptureRecorder()
        val staleTask = FakeCaptureTask()
        val staleToken = ownership.begin(staleLease)
        ownership.publishAndStart(staleToken, staleRecorder, staleTask)
        ownership.stop()
        val currentLease = FakeCaptureRouteLease {}
        val currentToken = ownership.begin(currentLease)

        ownership.terminate(staleToken, staleRecorder)

        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, currentLease.retireCalls)
        assertTrue(ownership.isCurrentLease(currentToken, currentLease))
    }

    @Test
    fun `recorder creation failure clears and retires exact acquired lease`() {
        val cause = IllegalArgumentException("factory failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }

        val thrown = runCatching {
            createVoiceAudioCaptureRecorder(
                createRecorder = { throw cause },
                clearRouteLease = { events += "routeCleared" },
                routeLease = lease,
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("AudioRecord creation failed", thrown?.message)
        assertTrue(thrown?.cause === cause)
        assertEquals(listOf("routeCleared", "routeRetired"), events)
        lease.retire()
        assertEquals(listOf("routeCleared", "routeRetired"), events)
    }

    @Test
    fun `recorder configuration failure releases recorder and exact route lease before escaping`() {
        val failure = IllegalStateException("configuration failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }

        val thrown = runCatching {
            configureVoiceAudioCaptureRecorder(
                configureRecorder = { throw failure },
                releaseRecorder = { events += "recorderReleased" },
                clearRouteLease = { events += "routeCleared" },
                routeLease = lease,
            )
        }.exceptionOrNull()

        assertTrue(thrown === failure)
        assertEquals(listOf("recorderReleased", "routeCleared", "routeRetired"), events)
        lease.retire()
        assertEquals(listOf("recorderReleased", "routeCleared", "routeRetired"), events)
    }

    @Test
    fun `uninitialized recorder retires installed route lease before setup error escapes`() {
        var directResourcesAcquired = false
        var retireCalls = 0
        var recorderReleaseCalls = 0
        var installedLease: VoiceAudioCaptureRouteLease? = null
        directResourcesAcquired = true
        val lease = FakeCaptureRouteLease {
            retireCalls += 1
            directResourcesAcquired = false
        }.also { installedLease = it }

        val thrown = runCatching {
            ensureVoiceAudioCaptureRecorderInitialized(
                initialized = false,
                releaseRecorder = { recorderReleaseCalls += 1 },
                clearRouteLease = {
                    if (installedLease === lease) installedLease = null
                },
                routeLease = lease,
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("AudioRecord initialization failed", thrown?.message)
        assertEquals(1, recorderReleaseCalls)
        assertEquals(null, installedLease)
        assertFalse(directResourcesAcquired)
        assertEquals(1, retireCalls)

        lease.retire() // later stop
        lease.retire() // later release

        assertEquals(1, retireCalls)
        assertFalse(directResourcesAcquired)
    }

    @Test
    fun `read exception autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                throw IllegalStateException("read failed")
            },
            onPcm16 = { events += "pcm" },
            onReadException = { events += "readException:${it.message}" },
            onNegativeRead = { events += "negative:$it" },
            onPcmCallbackException = { events += "callbackException:${it.message}" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(
            listOf("readException:read failed", "recorderRetired", "routeRetired"),
            events,
        )
    }

    @Test
    fun `negative read autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                -3
            },
            onPcm16 = { events += "pcm" },
            onReadException = { events += "readException" },
            onNegativeRead = { events += "negative:$it" },
            onPcmCallbackException = { events += "callbackException" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(listOf("negative:-3", "recorderRetired", "routeRetired"), events)
    }

    @Test
    fun `PCM callback exception autonomously retires exact capture route once with no later read`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                2
            },
            onPcm16 = { throw IllegalArgumentException("callback failed") },
            onReadException = { events += "readException" },
            onNegativeRead = { events += "negative" },
            onPcmCallbackException = { events += "callbackException:${it.message}" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(
            listOf("callbackException:callback failed", "recorderRetired", "routeRetired"),
            events,
        )
    }

    @Test
    fun `capture route lease is exact once across autonomous stop and release race`() {
        val retireCalls = AtomicInteger()
        val lease = FakeCaptureRouteLease { retireCalls.incrementAndGet() }
        val racers = List(3) {
            thread(name = "capture-retirement-$it") { lease.retire() }
        }

        racers.forEach { it.join(5_000) }

        assertTrue(racers.none(Thread::isAlive))
        assertEquals(1, retireCalls.get())
    }

    @Test
    fun `stop or release waits for autonomous route retirement before later mutation`() {
        val retirementEntered = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val competingRetirementCompleted = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            retirementEntered.countDown()
            releaseRetirement.await(5, TimeUnit.SECONDS)
        }
        val autonomous = thread(name = "autonomous-capture-retirement") { lease.retire() }
        assertTrue(retirementEntered.await(5, TimeUnit.SECONDS))
        val stopOrRelease = thread(name = "explicit-capture-retirement") {
            lease.retire()
            competingRetirementCompleted.countDown()
        }

        try {
            assertFalse(competingRetirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseRetirement.countDown()
            autonomous.join(5_000)
            stopOrRelease.join(5_000)
        }

        assertFalse(autonomous.isAlive)
        assertFalse(stopOrRelease.isAlive)
        assertTrue(competingRetirementCompleted.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `Telecom owner never constructs or calls direct controller`() {
        var directCreated = 0
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.Telecom) {
            directCreated += 1
            RecordingRouteController()
        }

        val lease = selected.acquireCapture()
        lease.retire()
        lease.retire()
        selected.close()

        assertEquals(0, directCreated)
    }

    @Test
    fun `direct fallback delegates lifecycle once`() {
        val direct = RecordingRouteController()
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.DirectFallback) { direct }

        val lease = selected.acquireCapture()
        lease.retire()
        lease.retire()
        selected.close()

        assertEquals(listOf("acquire", "retire", "close"), direct.calls)
    }

    @Test
    fun `capture lifecycle serializes overlapping transitions`() {
        val lifecycle = VoiceAudioCaptureLifecycle()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val first = thread(name = "voice-audio-first-transition") {
            lifecycle.transition {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = thread(name = "voice-audio-second-transition") {
            secondStarted.countDown()
            lifecycle.transition {
                secondEntered.countDown()
            }
        }

        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            first.join(5_000)
            second.join(5_000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(secondEntered.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `capture callback excludes overlapping transition and permits reentry`() {
        val lifecycle = VoiceAudioCaptureLifecycle()
        val callbackEntered = CountDownLatch(1)
        val reentrantTransitionEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val overlappingTransitionEntered = CountDownLatch(1)
        val callback = thread(name = "voice-audio-callback") {
            lifecycle.callback {
                callbackEntered.countDown()
                lifecycle.transition {
                    reentrantTransitionEntered.countDown()
                }
                releaseCallback.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        assertTrue(reentrantTransitionEntered.await(5, TimeUnit.SECONDS))
        val overlappingTransition = thread(name = "voice-audio-overlapping-transition") {
            lifecycle.transition {
                overlappingTransitionEntered.countDown()
            }
        }

        try {
            assertFalse(overlappingTransitionEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseCallback.countDown()
            callback.join(5_000)
            overlappingTransition.join(5_000)
        }
        assertFalse(callback.isAlive)
        assertFalse(overlappingTransition.isAlive)
        assertTrue(overlappingTransitionEntered.await(0, TimeUnit.MILLISECONDS))
    }

    private class RecordingRouteController : VoiceAudioRouteController {
        val calls = mutableListOf<String>()

        override fun acquireCapture(): VoiceAudioCaptureRouteLease {
            calls += "acquire"
            return FakeCaptureRouteLease { calls += "retire" }
        }

        override fun close() {
            calls += "close"
        }
    }

    private class FakeCaptureRouteLease(
        private val onRetire: () -> Unit,
    ) : VoiceAudioCaptureRouteLease {
        private val retirement = me.rerere.rikkahub.voiceagent.RetirementBarrier()

        var configureCalls = 0
            private set

        var retireCalls = 0
            private set

        override fun configureRecorder(recorder: AudioRecord) {
            configureCalls += 1
        }

        override fun retire() {
            retirement.retire {
                retireCalls += 1
                onRetire()
            }
        }
    }

    private fun fakeOwnership() = VoiceAudioCaptureOwnership(
        startRecorder = { recorder: FakeCaptureRecorder -> recorder.start() },
        isRecorderRecording = FakeCaptureRecorder::recording,
        stopRecorder = FakeCaptureRecorder::stop,
        releaseRecorder = FakeCaptureRecorder::release,
        startTask = FakeCaptureTask::start,
        cancelTask = FakeCaptureTask::cancel,
    )

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

    private class FakeCaptureTask {
        var startCalls = 0
            private set
        var cancelCalls = 0
            private set

        fun start() {
            startCalls += 1
        }

        fun cancel() {
            cancelCalls += 1
        }
    }
}
