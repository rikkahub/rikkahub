package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRouteControllerTest {
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
