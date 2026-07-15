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
    fun `read exception autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = VoiceAudioCaptureRouteLease { events += "afterCapture" }
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
            listOf("readException:read failed", "recorderRetired", "afterCapture"),
            events,
        )
    }

    @Test
    fun `negative read autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = VoiceAudioCaptureRouteLease { events += "afterCapture" }
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
        assertEquals(listOf("negative:-3", "recorderRetired", "afterCapture"), events)
    }

    @Test
    fun `PCM callback exception autonomously retires exact capture route once with no later read`() {
        val events = mutableListOf<String>()
        val lease = VoiceAudioCaptureRouteLease { events += "afterCapture" }
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
            listOf("callbackException:callback failed", "recorderRetired", "afterCapture"),
            events,
        )
    }

    @Test
    fun `capture route lease is exact once across autonomous stop and release race`() {
        val afterCaptureCalls = AtomicInteger()
        val lease = VoiceAudioCaptureRouteLease { afterCaptureCalls.incrementAndGet() }
        val racers = List(3) {
            thread(name = "capture-retirement-$it") { lease.retire() }
        }

        racers.forEach { it.join(5_000) }

        assertTrue(racers.none(Thread::isAlive))
        assertEquals(1, afterCaptureCalls.get())
    }

    @Test
    fun `stop or release waits for autonomous route retirement before later mutation`() {
        val afterCaptureEntered = CountDownLatch(1)
        val releaseAfterCapture = CountDownLatch(1)
        val competingRetirementCompleted = CountDownLatch(1)
        val lease = VoiceAudioCaptureRouteLease {
            afterCaptureEntered.countDown()
            releaseAfterCapture.await(5, TimeUnit.SECONDS)
        }
        val autonomous = thread(name = "autonomous-capture-retirement") { lease.retire() }
        assertTrue(afterCaptureEntered.await(5, TimeUnit.SECONDS))
        val stopOrRelease = thread(name = "explicit-capture-retirement") {
            lease.retire()
            competingRetirementCompleted.countDown()
        }

        try {
            assertFalse(competingRetirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseAfterCapture.countDown()
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

        selected.beforeCapture()
        selected.afterCapture()
        selected.close()

        assertEquals(0, directCreated)
    }

    @Test
    fun `direct fallback delegates lifecycle once`() {
        val direct = RecordingRouteController()
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.DirectFallback) { direct }

        selected.beforeCapture()
        selected.afterCapture()
        selected.close()

        assertEquals(listOf("before", "after", "close"), direct.calls)
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

        override fun beforeCapture() {
            calls += "before"
        }

        override fun configureRecorder(recorder: AudioRecord) = Unit

        override fun afterCapture() {
            calls += "after"
        }

        override fun close() {
            calls += "close"
        }
    }
}
