package me.rerere.rikkahub.voiceagent.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioCaptureCallbackAdmissionTest {
    @Test
    fun `normal PCM callback admitted before stop drains before cleanup`() {
        val fixture = activeFixture()
        val callbackEntered = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val callback = thread(name = "normal-pcm-callback") {
            assertTrue(fixture.ownership.runCallbackIfCurrent(fixture.token, fixture.recorder) {
                callbackEntered.countDown()
                allowCallbackReturn.await(5, TimeUnit.SECONDS)
            })
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val stop = thread(name = "normal-pcm-stop") {
            stopAttempted.countDown()
            fixture.ownership.stop()
            stopReturned.countDown()
        }

        try {
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<String>(), fixture.cleanupEvents)
        } finally {
            allowCallbackReturn.countDown()
            callback.join(5_000)
            stop.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(stop.isAlive)
        assertEquals(listOf("cancel", "stop", "release", "route"), fixture.cleanupEvents)
    }

    @Test
    fun `debug PCM callback admitted before release drains and release stays terminal`() {
        val fixture = activeFixture()
        val callbackEntered = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val callback = thread(name = "debug-pcm-callback") {
            assertTrue(fixture.ownership.runCallbackIfCurrent(fixture.token, fixture.recorder) {
                callbackEntered.countDown()
                allowCallbackReturn.await(5, TimeUnit.SECONDS)
            })
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val release = thread(name = "debug-pcm-release") {
            fixture.ownership.release()
            releaseReturned.countDown()
        }

        try {
            assertFalse(releaseReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<String>(), fixture.cleanupEvents)
        } finally {
            allowCallbackReturn.countDown()
            callback.join(5_000)
            release.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(release.isAlive)
        assertTrue(fixture.ownership.isReleased())
        assertTrue(runCatching { fixture.ownership.reserve() }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("cancel", "stop", "release", "route"), fixture.cleanupEvents)
    }

    @Test
    fun `replacement capture waits for admitted callback and rejects stale admission`() {
        val fixture = activeFixture()
        val callbackEntered = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val replacementPublished = CountDownLatch(1)
        val currentToken = AtomicReference<VoiceAudioCaptureToken>()
        val currentRecorder = AtomicReference<FakeRecorder>()
        val callback = thread(name = "replacement-stale-callback") {
            fixture.ownership.runCallbackIfCurrent(fixture.token, fixture.recorder) {
                callbackEntered.countDown()
                allowCallbackReturn.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val replacement = thread(name = "replacement-capture") {
            fixture.ownership.stop()
            val token = fixture.ownership.reserve()
            val recorder = FakeRecorder()
            assertTrue(fixture.ownership.publishRoute(token, FakeRouteLease(fixture.cleanupEvents)))
            assertEquals(
                VoiceAudioCaptureStartOutcome.Started,
                fixture.ownership.publishAndStart(token, recorder, FakeTask()),
            )
            currentToken.set(token)
            currentRecorder.set(recorder)
            replacementPublished.countDown()
        }

        try {
            assertFalse(replacementPublished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowCallbackReturn.countDown()
            callback.join(5_000)
            replacement.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(replacement.isAlive)
        assertFalse(fixture.ownership.runCallbackIfCurrent(fixture.token, fixture.recorder) {})
        assertTrue(fixture.ownership.runCallbackIfCurrent(currentToken.get(), currentRecorder.get()) {})
        fixture.ownership.stop()
    }

    @Test
    fun `reentrant stop defers cleanup and external release joins exact retirement`() {
        val fixture = activeFixture()
        val reentrantStopReturned = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val callback = thread(name = "reentrant-stop-callback") {
            fixture.ownership.runCallbackIfCurrent(fixture.token, fixture.recorder) {
                fixture.ownership.stop()
                reentrantStopReturned.countDown()
                allowCallbackReturn.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(reentrantStopReturned.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), fixture.cleanupEvents)
        val release = thread(name = "reentrant-stop-release-joiner") {
            fixture.ownership.release()
            releaseReturned.countDown()
        }

        try {
            assertFalse(releaseReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<String>(), fixture.cleanupEvents)
        } finally {
            allowCallbackReturn.countDown()
            callback.join(5_000)
            release.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(release.isAlive)
        assertTrue(fixture.ownership.isReleased())
        assertEquals(listOf("cancel", "stop", "release", "route"), fixture.cleanupEvents)
    }

    @Test
    fun `reentrant autonomous termination resumes on injection completion callback exit`() {
        val fixture = activeFixture()

        val admitted = fixture.ownership.runCallbackIfCurrent(fixture.token, fixture.recorder) {
            assertTrue(fixture.ownership.terminate(fixture.token, fixture.recorder))
            assertEquals(emptyList<String>(), fixture.cleanupEvents)
        }

        assertTrue(admitted)
        assertEquals(listOf("cancel", "stop", "release", "route"), fixture.cleanupEvents)
        val nextToken = fixture.ownership.reserve()
        assertTrue(fixture.ownership.abort(nextToken))
    }

    private fun activeFixture(): Fixture {
        val events = CopyOnWriteArrayList<String>()
        val ownership = VoiceAudioCaptureOwnership<FakeRecorder, FakeTask>(
            startRecorder = {},
            isRecorderRecording = { true },
            stopRecorder = { events += "stop" },
            releaseRecorder = { events += "release" },
            startTask = { true },
            cancelTask = { events += "cancel" },
        )
        val token = ownership.reserve()
        val recorder = FakeRecorder()
        assertTrue(ownership.publishRoute(token, FakeRouteLease(events)))
        assertEquals(
            VoiceAudioCaptureStartOutcome.Started,
            ownership.publishAndStart(token, recorder, FakeTask()),
        )
        return Fixture(ownership, token, recorder, events)
    }

    private data class Fixture(
        val ownership: VoiceAudioCaptureOwnership<FakeRecorder, FakeTask>,
        val token: VoiceAudioCaptureToken,
        val recorder: FakeRecorder,
        val cleanupEvents: CopyOnWriteArrayList<String>,
    )

    private class FakeRecorder
    private class FakeTask

    private class FakeRouteLease(
        private val events: MutableList<String>,
    ) : VoiceAudioCaptureRouteLease {
        override suspend fun prepare() = Unit

        override fun configureRecorder(recorder: android.media.AudioRecord) = Unit

        override fun retire() {
            events += "route"
        }
    }
}
