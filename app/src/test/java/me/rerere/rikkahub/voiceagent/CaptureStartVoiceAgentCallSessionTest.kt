package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.RecordingVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceAttributes
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceSpan
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartVoiceAgentCallSessionTest {
    @Test
    fun `mute joins admitted PCM before sending stream end`() = runTest {
        val fixture = connectedSession(initiallyMuted = false)
        val blockedSend = fixture.gemini.blockNextAudioSend()
        val pcm = launch(Dispatchers.Default) {
            fixture.audio.emitCapture(byteArrayOf(1, 2, 3))
        }
        assertTrue(blockedSend.started.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val muteReturned = CountDownLatch(1)
        val mute = launch(Dispatchers.Default) {
            fixture.session.setMuted(true)
            muteReturned.countDown()
        }

        val returnedBeforePcm = muteReturned.await(100, TimeUnit.MILLISECONDS)
        blockedSend.release.countDown()
        pcm.join()
        mute.join()

        assertFalse("mute must join admitted PCM", returnedBeforePcm)
        assertEquals(listOf("audio", "stream_end"), fixture.gemini.audioControlEvents)
        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        fixture.session.closeNow()
    }

    @Test
    fun `reentrant mute defers cleanup until other admitted PCM also leaves`() = runTest {
        val fixture = connectedSession(initiallyMuted = false)
        val firstBlockedSend = fixture.gemini.blockNextAudioSend()
        val firstPcm = launch(Dispatchers.Default) {
            fixture.audio.emitCapture(byteArrayOf(1))
        }
        assertTrue(firstBlockedSend.started.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val reentrantMuteReturned = CountDownLatch(1)
        fixture.gemini.onBeforeAudioSend = {
            fixture.gemini.onBeforeAudioSend = null
            fixture.session.setMuted(true)
            reentrantMuteReturned.countDown()
        }

        val secondPcm = launch(Dispatchers.Default) {
            fixture.audio.emitCapture(byteArrayOf(2))
        }
        assertTrue(reentrantMuteReturned.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        secondPcm.join()

        assertFalse(
            "reentrant cleanup must remain deferred while another PCM effect is admitted",
            fixture.session.state.value.audio == VoiceAudioStatus.Muted,
        )
        firstBlockedSend.release.countDown()
        firstPcm.join()

        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        assertEquals("stream_end", fixture.gemini.audioControlEvents.last())
        fixture.session.closeNow()
    }

    @Test
    fun `mute joins claimed debug completion before its final stream end`() = runTest {
        val fixture = connectedSession(initiallyMuted = false)
        val blockedEnd = fixture.gemini.blockNextAudioStreamEnd()
        val debug = launch(Dispatchers.Default) {
            fixture.audio.completeDebugInjection()
        }
        assertTrue(blockedEnd.started.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val muteReturned = CountDownLatch(1)
        val mute = launch(Dispatchers.Default) {
            fixture.session.setMuted(true)
            muteReturned.countDown()
        }

        val returnedBeforeDebug = muteReturned.await(100, TimeUnit.MILLISECONDS)
        blockedEnd.release.countDown()
        debug.join()
        mute.join()

        assertFalse("mute must join claimed debug completion", returnedBeforeDebug)
        assertEquals(listOf("stream_end", "stream_end"), fixture.gemini.audioControlEvents)
        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        fixture.session.closeNow()
    }

    @Test
    fun `mute joins committed capture before capture started observability`() = runTest {
        val observability = BlockingCaptureObservability()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = connectedSession(
            initiallyMuted = true,
            sessionScope = sessionScope,
            observability = observability,
        )
        val blockedStarted = observability.blockNextCaptureStarted()
        fixture.session.setMuted(false)
        assertTrue(blockedStarted.started.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val muteReturned = CountDownLatch(1)
        val mute = launch(Dispatchers.Default) {
            fixture.session.setMuted(true)
            muteReturned.countDown()
        }

        val returnedBeforeEvent = muteReturned.await(100, TimeUnit.MILLISECONDS)
        blockedStarted.release.countDown()
        mute.join()

        assertFalse("mute must join admitted capture-start observability", returnedBeforeEvent)
        assertEquals(
            listOf(
                "hermes_voice.mobile.audio.capture_started",
                "hermes_voice.mobile.audio.capture_muted",
            ),
            observability.eventNames.takeLast(2),
        )
        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        sessionScope.cancel()
    }

    @Test
    fun `audio status listener can reenter mute and leaves final status muted`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = connectedSession(
            initiallyMuted = true,
            sessionScope = sessionScope,
            diagnostics = diagnostics,
        )
        val listenerEntered = CountDownLatch(1)
        val mutedOnce = AtomicBoolean()
        val removeListener = diagnostics.addListener { event ->
            if (
                event.name == "audio_status" &&
                event.detail == "listening" &&
                mutedOnce.compareAndSet(false, true)
            ) {
                fixture.session.setMuted(true)
                listenerEntered.countDown()
            }
        }

        fixture.session.setMuted(false)
        assertTrue(listenerEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.session.state.value.audio != VoiceAudioStatus.Muted) yield()
        }

        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        removeListener()
        sessionScope.cancel()
    }

    @Test
    fun `late rejected start cleanup cannot stop a newer unmute capture`() = runTest {
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = connectedSession(initiallyMuted = true, sessionScope = sessionScope)
        val old = fixture.audio.suspendNextUncancellableStartCapture()
        fixture.session.setMuted(false)
        awaitCaptureSignal(old.entered)
        fixture.session.setMuted(true)
        awaitCaptureSignal(old.cancelled)
        val lateStop = fixture.audio.blockNextStopCapture()
        old.release.complete(Unit)
        assertTrue(lateStop.started.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val newer = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        yield()

        assertFalse("new start must wait for the old global stop", newer.entered.isCompleted)
        lateStop.release.countDown()
        awaitCaptureSignal(newer.entered)
        newer.release.complete(Unit)
        awaitCaptureSignal(newer.installed)
        fixture.audio.emitCapture(byteArrayOf(4, 5, 6))

        assertEquals(1, fixture.gemini.audioMessages.size)
        assertEquals(VoiceAudioStatus.UserSpeaking, fixture.session.state.value.audio)
        sessionScope.cancel()
    }

    @Test
    fun `mute wins against suspended initial capture completion`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val observability = RecordingVoiceObservability()
        val suspended = audio.suspendNextStartCapture()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        awaitCaptureSignal(suspended.entered)
        session.setMuted(true)

        suspended.release.complete(Unit)
        awaitCaptureSignal(suspended.installed)
        audio.emitCapture(byteArrayOf(1, 2, 3))
        audio.completeDebugInjection()
        yield()

        assertTrue(gemini.audioMessages.isEmpty())
        assertEquals(VoiceAudioStatus.Muted, session.state.value.audio)
        assertEquals(2, audio.stopCaptureCalls)
        assertFalse(
            observability.events.any { it.name == "hermes_voice.mobile.audio.capture_started" }
        )
        session.closeNow()
    }

    @Test
    fun `mute cancels suspended unmute capture before callback installation`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.setMuted(true)

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        delay(10)
        assertFalse(suspended.installed.isCompleted)
        assertEquals(1, fixture.audio.startCaptureCalls)
        assertTrue(fixture.audio.stopCaptureCalls >= 1)
        fixture.session.closeNow()
    }

    @Test
    fun `manual reconnect cancels suspended unmute capture before cleanup continues`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.reconnect()

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        delay(10)
        assertFalse(suspended.installed.isCompleted)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
        fixture.session.closeNow()
    }

    @Test
    fun `end cancels suspended unmute capture before cleanup continues`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.end()

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.audio.releaseCalls < 1) delay(10)
        }
        assertFalse(suspended.installed.isCompleted)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
    }

    @Test
    fun `close now cancels suspended unmute capture before cleanup continues`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.closeNow()

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        delay(10)
        assertFalse(suspended.installed.isCompleted)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
    }

    @Test
    fun `unmute capture failure is handled without an uncaught supervisor child failure`() = runTest {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val sessionScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val observability = RecordingVoiceObservability()
        val diagnostics = VoiceDiagnostics()
        val fixture = connectedMutedSession(
            sessionScope = sessionScope,
            observability = observability,
            diagnostics = diagnostics,
        )
        fixture.audio.startCaptureError = IllegalStateException("microphone revoked")

        fixture.session.setMuted(false)

        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.session.state.value.session !is VoiceSessionStatus.Error) delay(10)
        }
        assertTrue(uncaught.isEmpty())
        assertEquals(VoiceSessionStatus.Error("microphone revoked"), fixture.session.state.value.session)
        assertFalse(fixture.session.hasOwnedCaptureStartJob())
        assertEquals(1, fixture.audio.startCaptureCalls)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "audio_capture_failure" && it.detail.contains("microphone revoked")
            }
        )
        assertEquals(
            listOf("audio_capture_failure" to "audio_capture_failure"),
            observability.events
                .filter { it.name == "hermes_voice.mobile.session.failed" }
                .map {
                    it.attributes["session.end_reason"] to it.attributes["session.failure.kind"]
                },
        )
        sessionScope.cancel()
    }

    @Test
    fun `unmute capture failure runs later cleanup stages and records combined failures`() = runTest {
        val captureFailure = IllegalStateException("microphone revoked")
        val stopFailure = IllegalArgumentException("capture stop failed")
        val suppressFailure = UnsupportedOperationException("playback suppression failed")
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val sessionScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val diagnostics = VoiceDiagnostics()
        val fixture = connectedMutedSession(
            sessionScope = sessionScope,
            diagnostics = diagnostics,
        )
        val cleanupEvents = mutableListOf<String>()
        fixture.audio.apply {
            startCaptureError = captureFailure
            stopCaptureError = stopFailure
            suppressPlaybackError = suppressFailure
            onStopCapture = { cleanupEvents += "audio.stopCapture" }
            onSuppressPlayback = { cleanupEvents += "audio.suppressPlayback" }
        }
        fixture.gemini.onClose = { cleanupEvents += "gemini.close" }

        fixture.session.setMuted(false)

        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.session.state.value.session !is VoiceSessionStatus.Error) delay(10)
        }
        assertTrue(uncaught.isEmpty())
        assertEquals(VoiceSessionStatus.Error("microphone revoked"), fixture.session.state.value.session)
        assertEquals(
            listOf("audio.stopCapture", "audio.suppressPlayback", "gemini.close"),
            cleanupEvents,
        )
        val failureDiagnostic = diagnostics.events.value.last {
            it.name == VoiceSessionStopReason.AudioCaptureFailure.diagnosticReason
        }
        assertTrue(failureDiagnostic.detail.contains("microphone revoked"))
        assertTrue(failureDiagnostic.detail.contains("capture stop failed"))
        assertTrue(failureDiagnostic.detail.contains("playback suppression failed"))
        sessionScope.cancel()
    }

    private suspend fun CoroutineScope.connectedMutedSession(
        sessionScope: CoroutineScope = this,
        observability: RecordingVoiceObservability = RecordingVoiceObservability(),
        diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    ): CaptureCancellationFixture = connectedSession(
        initiallyMuted = true,
        sessionScope = sessionScope,
        observability = observability,
        diagnostics = diagnostics,
    )

    private suspend fun CoroutineScope.connectedSession(
        initiallyMuted: Boolean,
        sessionScope: CoroutineScope = this,
        observability: VoiceObservability = RecordingVoiceObservability(),
        diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    ): CaptureCancellationFixture {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = newSession(
            gemini = gemini,
            audio = audio,
            scope = sessionScope,
            observability = observability,
            diagnostics = diagnostics,
        )
        if (initiallyMuted) session.setMuted(true)
        session.start()
        gemini.awaitConnect()
        withTimeout(TEST_TIMEOUT_MS) {
            while (session.state.value.session != VoiceSessionStatus.Connected) delay(10)
        }
        return CaptureCancellationFixture(session = session, gemini = gemini, audio = audio)
    }

    private fun newSession(
        gemini: FakeGeminiLiveVoiceClient,
        audio: FakeVoiceAudioEngine,
        scope: CoroutineScope,
        observability: VoiceObservability = RecordingVoiceObservability(),
        diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    ) = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            observability = observability,
            scope = scope,
        )

    private suspend fun awaitCaptureSignal(signal: Deferred<Unit>) {
        withTimeout(TEST_TIMEOUT_MS) { signal.await() }
    }

    private fun VoiceAgentCallSession.hasOwnedCaptureStartJob(): Boolean {
        val controller = VoiceAgentCallSession::class.java
            .getDeclaredField("captureStartController")
            .also { it.isAccessible = true }
            .get(this) as VoiceCaptureStartController
        return controller.hasOwnedJob()
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private data class CaptureCancellationFixture(
        val session: VoiceAgentCallSession,
        val gemini: FakeGeminiLiveVoiceClient,
        val audio: FakeVoiceAudioEngine,
    )

    private class BlockingCaptureObservability : VoiceObservability {
        private val delegate = RecordingVoiceObservability()
        private var blockedCaptureStarted: BlockedPlayback? = null
        val eventNames: List<String>
            get() = synchronized(this) { delegate.events.map { it.name } }

        fun blockNextCaptureStarted(): BlockedPlayback = BlockedPlayback().also { blocked ->
            synchronized(this) {
                blockedCaptureStarted = blocked
            }
        }

        override fun recordEvent(name: String, trace: VoiceTraceContext, attributes: VoiceAttributes) {
            if (name == "hermes_voice.mobile.audio.capture_started") {
                synchronized(this) {
                    blockedCaptureStarted.also { blockedCaptureStarted = null }
                }?.awaitRelease()
            }
            synchronized(this) {
                delegate.recordEvent(name = name, trace = trace, attributes = attributes)
            }
        }

        override suspend fun <T> withSpan(
            name: String,
            trace: VoiceTraceContext,
            block: suspend (VoiceSpan) -> T,
        ): T = delegate.withSpan(name = name, trace = trace, block = block)

        override fun captureException(
            throwable: Throwable,
            trace: VoiceTraceContext,
            attributes: VoiceAttributes,
        ) = delegate.captureException(throwable = throwable, trace = trace, attributes = attributes)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 500L
    }
}
