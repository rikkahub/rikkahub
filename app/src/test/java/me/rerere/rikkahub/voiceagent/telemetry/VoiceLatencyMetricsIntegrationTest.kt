package me.rerere.rikkahub.voiceagent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLatencyMetricsIntegrationTest {
    @Test
    fun `coordinator buffers fast playback arrival during pending speech-end confirmation`() {
        val recordedEvents = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fakeObservability = object : VoiceObservability {
            override fun recordEvent(name: String, trace: VoiceTraceContext, attributes: VoiceAttributes) {
                recordedEvents.add(name to attributes)
            }
            override suspend fun <T> withSpan(name: String, trace: VoiceTraceContext, block: suspend (VoiceSpan) -> T): T = error("")
            override fun captureException(throwable: Throwable, trace: VoiceTraceContext, attributes: VoiceAttributes) = Unit
        }

        val trace = VoiceTraceContext(traceId = "trace-1", voiceSessionId = "session-1")
        val coordinator = VoiceLatencyTelemetryCoordinator(
            traceContext = trace,
            transport = "LiveKit",
            observability = fakeObservability,
        )

        coordinator.onSpeechStart(1_000_000_000L)
        // Playback arrives while speech-end is still pending
        coordinator.onPlaybackStart(1_350_000_000L)
        coordinator.onSpeechEnd(1_200_000_000L)

        val ttfaEvent = recordedEvents.firstOrNull { it.first == "voice.latency.ttfa" }
        requireNotNull(ttfaEvent)
        assertEquals(150L, ttfaEvent.second["ttfa_client_ms"])
        assertEquals("LiveKit", ttfaEvent.second["transport"])
    }

    @Test
    fun `silence cancellation invalidates pending playback buffer and prevents cross-turn pairing`() {
        val recordedEvents = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fakeObservability = object : VoiceObservability {
            override fun recordEvent(name: String, trace: VoiceTraceContext, attributes: VoiceAttributes) {
                recordedEvents.add(name to attributes)
            }
            override suspend fun <T> withSpan(name: String, trace: VoiceTraceContext, block: suspend (VoiceSpan) -> T): T = error("")
            override fun captureException(throwable: Throwable, trace: VoiceTraceContext, attributes: VoiceAttributes) = Unit
        }

        val trace = VoiceTraceContext(traceId = "trace-2", voiceSessionId = "session-2")
        val coordinator = VoiceLatencyTelemetryCoordinator(
            traceContext = trace,
            transport = "LiveKit",
            observability = fakeObservability,
        )

        coordinator.onSpeechStart(1_000_000_000L)
        coordinator.onPlaybackStart(1_200_000_000L)
        coordinator.onSilenceCandidateCancelled(1_250_000_000L)

        // Then user finishes speaking at t=2000ms
        coordinator.onSpeechEnd(2_000_000_000L)

        assertTrue("Stale playback must not pair after silence cancellation", recordedEvents.none { it.first == "voice.latency.ttfa" })
    }

    @Test
    fun `cutoff latency measures time from speech start to playback stop on interruption`() {
        val recordedEvents = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fakeObservability = object : VoiceObservability {
            override fun recordEvent(name: String, trace: VoiceTraceContext, attributes: VoiceAttributes) {
                recordedEvents.add(name to attributes)
            }
            override suspend fun <T> withSpan(name: String, trace: VoiceTraceContext, block: suspend (VoiceSpan) -> T): T = error("")
            override fun captureException(throwable: Throwable, trace: VoiceTraceContext, attributes: VoiceAttributes) = Unit
        }

        val trace = VoiceTraceContext(traceId = "trace-3", voiceSessionId = "session-3")
        val coordinator = VoiceLatencyTelemetryCoordinator(
            traceContext = trace,
            transport = "DirectGemini",
            observability = fakeObservability,
        )

        // User interrupts ongoing playback at t=1000ms
        coordinator.onSpeechStart(1_000_000_000L)
        // Assistant playback stops at t=1080ms
        coordinator.onPlaybackStop(1_080_000_000L)

        val cutoffEvent = recordedEvents.firstOrNull { it.first == "voice.latency.cutoff" }
        requireNotNull(cutoffEvent)
        assertEquals(80L, cutoffEvent.second["cutoff_client_ms"])
        assertEquals("DirectGemini", cutoffEvent.second["transport"])
    }
}
