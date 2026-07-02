package me.rerere.rikkahub.voiceagent.telemetry

import io.sentry.NoOpTransportFactory
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceObservabilityTest {
    private val trace = VoiceTraceContext(
        traceId = "trace-123",
        voiceSessionId = "session-456",
    )

    @Test
    fun `new voice trace context uses same short id for trace and session`() {
        val trace = newVoiceTraceContext(randomInt = { 42 })

        assertEquals("VA000042", trace.traceId)
        assertEquals("VA000042", trace.voiceSessionId)
        assertNull(trace.sentryTrace)
        assertNull(trace.sentryBaggage)
    }

    @Test
    fun `new voice trace id formats six digit voice agent id`() {
        val values = listOf(0, 7, 999999)
        val traceIds = values.map { value -> newVoiceTraceId(randomInt = { value }) }

        assertEquals(listOf("VA000000", "VA000007", "VA999999"), traceIds)
        traceIds.forEach { traceId ->
            assertTrue(traceId.matches(Regex("^VA[0-9]{6}$")))
        }
    }

    @Test
    fun `new voice trace id always uses ascii digits regardless of default locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))

            val traceId = newVoiceTraceId(randomInt = { 42 })

            assertEquals("VA000042", traceId)
            assertTrue(traceId.matches(Regex("^VA[0-9]{6}$")))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `sentry observability emits message event with trace attributes`() {
        val capturedEvents = mutableListOf<SentryEvent>()

        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.setBeforeSend { event, _ ->
                capturedEvents += event
                event
            }
        }

        try {
            SentryVoiceObservability().recordEvent(
                name = "voicelab.mobile.session.started",
                trace = trace,
                attributes = mapOf(
                    "prompt" to "status",
                    "ignored" to null,
                ),
            )
            Sentry.flush(1_000)
        } finally {
            Sentry.close()
        }

        val event = capturedEvents.single()
        assertEquals("voicelab.mobile.session.started", event.message?.formatted)
        assertEquals(SentryLevel.INFO, event.level)
        assertEquals("android", event.getTag("service"))
        assertEquals("trace-123", event.getTag("voice_trace_id"))
        assertEquals("session-456", event.getTag("voice_session_id"))
        assertEquals("android", event.getExtra("service"))
        assertEquals("trace-123", event.getExtra("traceId"))
        assertEquals("session-456", event.getExtra("voiceSessionId"))
        assertEquals("status", event.getExtra("prompt"))
        assertFalse(event.extras.orEmpty().containsKey("ignored"))
    }

    @Test
    fun `sentry observability sets correlation tags from canonical and alias attributes`() {
        val capturedEvents = mutableListOf<SentryEvent>()

        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.setBeforeSend { event, _ ->
                capturedEvents += event
                event
            }
        }

        try {
            SentryVoiceObservability().recordEvent(
                name = "voicelab.mobile.hermes_tool.completed",
                trace = trace,
                attributes = mapOf(
                    "conversationId" to "conversation-123",
                    "jobId" to "job-456",
                    "gemini.tool_call.call_id" to "call-789",
                    "existing" to "extra",
                ),
            )
            Sentry.flush(1_000)
        } finally {
            Sentry.close()
        }

        val event = capturedEvents.single()
        assertEquals("conversation-123", event.getTag("conversationId"))
        assertEquals("call-789", event.getTag("callId"))
        assertEquals("job-456", event.getTag("hermes_job_id"))
        assertEquals("conversation-123", event.getExtra("conversationId"))
        assertEquals("call-789", event.getExtra("callId"))
        assertEquals("call-789", event.getExtra("gemini.tool_call.call_id"))
        assertEquals("job-456", event.getExtra("jobId"))
        assertEquals("job-456", event.getExtra("hermes_job_id"))
        assertEquals("extra", event.getExtra("existing"))
    }

    @Test
    fun `sentry observability adds propagation headers to trace context`() {
        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 1.0
        }

        try {
            val enriched = SentryVoiceObservability().withSentryPropagation(trace)

            assertEquals("trace-123", enriched.traceId)
            assertEquals("session-456", enriched.voiceSessionId)
            assertTrue(enriched.sentryTrace.orEmpty().contains("-"))
            assertTrue(enriched.sentryBaggage.orEmpty().contains("sentry-"))
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `sentry observability keeps voice transactions isolated by trace context`() {
        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 1.0
        }

        try {
            val observability = SentryVoiceObservability()
            val firstTrace = trace
            val secondTrace = VoiceTraceContext(
                traceId = "trace-789",
                voiceSessionId = "session-999",
            )

            val first = observability.withSentryPropagation(firstTrace)
            val second = observability.withSentryPropagation(secondTrace)

            assertTrue(first.sentryTrace.orEmpty().isNotBlank())
            assertTrue(second.sentryTrace.orEmpty().isNotBlank())
            assertTrue(first.sentryTrace != second.sentryTrace)

            val firstAgain = observability.withSentryPropagation(firstTrace)
            assertEquals(first.sentryTrace, firstAgain.sentryTrace)

            observability.recordEvent("voicelab.mobile.session.ended", firstTrace)

            val firstAfterEnd = observability.withSentryPropagation(firstTrace)
            assertTrue(firstAfterEnd.sentryTrace.orEmpty().isNotBlank())
            assertFalse(first.sentryTrace == firstAfterEnd.sentryTrace)

            val secondAgain = observability.withSentryPropagation(secondTrace)
            assertEquals(second.sentryTrace, secondAgain.sentryTrace)
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `sentry observability closes voice transaction on session failed`() {
        val transactionStatuses = mutableListOf<SpanStatus?>()
        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 1.0
            options.setBeforeSendTransaction { transaction, _ ->
                transactionStatuses += transaction.status
                transaction
            }
        }

        try {
            val observability = SentryVoiceObservability()
            val first = observability.withSentryPropagation(trace)
            val endedTrace = VoiceTraceContext(
                traceId = "trace-ended",
                voiceSessionId = "session-ended",
            )
            observability.withSentryPropagation(endedTrace)

            observability.recordEvent("voicelab.mobile.session.failed", first)
            observability.recordEvent("voicelab.mobile.session.ended", endedTrace)
            Sentry.flush(1_000)

            val afterFailure = observability.withSentryPropagation(trace)
            assertTrue(afterFailure.sentryTrace.orEmpty().isNotBlank())
            assertFalse(first.sentryTrace == afterFailure.sentryTrace)
            assertTrue(transactionStatuses.contains(SpanStatus.INTERNAL_ERROR))
            assertTrue(transactionStatuses.contains(SpanStatus.OK))
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `sentry observability creates voice transaction instead of reusing ambient span`() {
        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 1.0
        }

        val ambientTransaction = Sentry.startTransaction("ambient", "ambient")
        val ambientToken = ambientTransaction.makeCurrent()
        try {
            val enriched = SentryVoiceObservability().withSentryPropagation(trace)

            assertTrue(enriched.sentryTrace.orEmpty().isNotBlank())
            assertFalse(enriched.sentryTrace == ambientTransaction.toSentryTrace().value)
        } finally {
            ambientToken.close()
            ambientTransaction.finish()
            Sentry.close()
        }
    }

    @Test
    fun `sentry observability does not emit propagation from unsampled no-op transactions`() {
        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 0.0
        }

        try {
            val enriched = SentryVoiceObservability().withSentryPropagation(trace)

            assertNull(enriched.sentryTrace)
            assertNull(enriched.sentryBaggage)
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `sentry observability records failed span and exception with trace attributes`() {
        val capturedEvents = mutableListOf<SentryEvent>()
        val error = IllegalStateException("boom")

        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 1.0
            options.setBeforeSend { event, _ ->
                capturedEvents += event
                event
            }
        }

        try {
            val observability = SentryVoiceObservability()
            val enriched = observability.withSentryPropagation(trace)
            val thrown = runCatching {
                runBlocking {
                    observability.withSpan("voice.span", enriched) { span ->
                        span.setAttribute("step", "tool")
                        throw error
                    }
                }
            }.exceptionOrNull()

            Sentry.flush(1_000)

            assertSame(error, thrown)
            assertEquals(
                listOf("voice.span.started", "voice.span.failed"),
                capturedEvents.mapNotNull { event -> event.message?.formatted },
            )
            val failedEvent = capturedEvents.single { event -> event.message?.formatted == "voice.span.failed" }
            assertEquals("android", failedEvent.getTag("service"))
            assertEquals("trace-123", failedEvent.getTag("voice_trace_id"))
            assertEquals("session-456", failedEvent.getTag("voice_session_id"))
            assertEquals("android", failedEvent.getExtra("service"))
            assertEquals("trace-123", failedEvent.getExtra("traceId"))
            assertEquals("session-456", failedEvent.getExtra("voiceSessionId"))
            assertEquals("tool", failedEvent.getExtra("step"))
            val exceptionEvent = capturedEvents.single { event ->
                event.exceptions.orEmpty().any { exception -> exception.value == "boom" }
            }
            assertEquals("android", exceptionEvent.getTag("service"))
            assertEquals("trace-123", exceptionEvent.getTag("voice_trace_id"))
            assertEquals("session-456", exceptionEvent.getTag("voice_session_id"))
            assertEquals("android", exceptionEvent.getExtra("service"))
            assertEquals("trace-123", exceptionEvent.getExtra("traceId"))
            assertEquals("session-456", exceptionEvent.getExtra("voiceSessionId"))
            assertEquals("tool", exceptionEvent.getExtra("step"))
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `sentry observability does not record cancellation as failed span`() {
        val capturedEvents = mutableListOf<SentryEvent>()

        Sentry.init { options ->
            options.dsn = "https://public@example.com/1"
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.tracesSampleRate = 1.0
            options.setBeforeSend { event, _ ->
                capturedEvents += event
                event
            }
        }

        try {
            val observability = SentryVoiceObservability()
            val enriched = observability.withSentryPropagation(trace)
            val thrown = runCatching {
                runBlocking {
                    observability.withSpan("voice.span", enriched) {
                        throw CancellationException("cancelled")
                    }
                }
            }.exceptionOrNull()

            Sentry.flush(1_000)

            assertTrue(thrown is CancellationException)
            assertEquals(
                listOf("voice.span.started"),
                capturedEvents.mapNotNull { event -> event.message?.formatted },
            )
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `no-op observability executes span callback`() = runBlocking {
        val result = NoOpVoiceObservability.withSpan("voice.span", trace) { span ->
            span.setAttribute("step", "started")
            span.setAttributes(mapOf("ignored" to null))
            "done"
        }

        assertEquals("done", result)
    }

    @Test
    fun `recording observability stores events spans and exceptions without null attributes`() = runBlocking {
        val observability = RecordingVoiceObservability()
        val error = IllegalStateException("boom")

        observability.recordEvent(
            name = "voice.event",
            trace = trace,
            attributes = mapOf(
                "prompt" to "status",
                "ignored" to null,
            ),
        )
        val spanResult = observability.withSpan("voice.span", trace) { span ->
            span.setAttribute("step", "route")
            span.setAttributes(
                mapOf(
                    "ok" to true,
                    "ignored" to null,
                )
            )
            "span-result"
        }
        observability.captureException(
            throwable = error,
            trace = trace,
            attributes = mapOf(
                "route" to "hermes",
                "ignored" to null,
            ),
        )

        assertEquals("span-result", spanResult)
        assertEquals(
            listOf(
                RecordedVoiceEvent(
                    name = "voice.event",
                    trace = trace,
                    attributes = mapOf("prompt" to "status"),
                )
            ),
            observability.events,
        )
        assertEquals(
            listOf(
                RecordedVoiceSpan(
                    name = "voice.span",
                    trace = trace,
                    attributes = mapOf("step" to "route", "ok" to true),
                    status = RecordedVoiceSpanStatus.Ok,
                )
            ),
            observability.spans,
        )
        assertEquals(1, observability.exceptions.size)
        assertSame(error, observability.exceptions.single().throwable)
        assertEquals(mapOf("route" to "hermes"), observability.exceptions.single().attributes)
    }

    @Test
    fun `recording observability records failed spans before rethrowing`() = runBlocking {
        val observability = RecordingVoiceObservability()
        val error = IllegalStateException("boom")

        val thrown = runCatching {
            observability.withSpan("voice.span", trace) { span ->
                span.setAttribute("step", "route")
                throw error
            }
        }.exceptionOrNull()

        assertSame(error, thrown)
        assertEquals(
            listOf(
                RecordedVoiceSpan(
                    name = "voice.span",
                    trace = trace,
                    attributes = mapOf("step" to "route"),
                    status = RecordedVoiceSpanStatus.Error,
                )
            ),
            observability.spans,
        )
    }

    @Test
    fun `voice text payload keeps preview metadata and sha256`() {
        val attributes = voiceTextPayload(
            key = "prompt",
            text = "alpha beta gamma",
            previewChars = 10,
        )

        assertEquals("alpha beta", attributes["prompt"])
        assertEquals(true, attributes["prompt.truncated"])
        assertEquals(16, attributes["prompt.chars"])
        assertEquals(
            "64989ccbf3efa9c84e2afe7cee9bc5828bf0fcb91e44f8c1e591638a2c2e90e3",
            attributes["prompt.sha256"],
        )
        assertFalse(attributes.containsKey("prompt.normalized"))
    }

    @Test
    fun `voice domain text payload emits canonical key metadata`() {
        val attributes = voiceTextPayload(
            key = "voice.user_transcript",
            text = "hello private user",
            previewChars = 8,
        )

        assertEquals("hello pr", attributes["voice.user_transcript"])
        assertEquals(true, attributes["voice.user_transcript.truncated"])
        assertEquals(18, attributes["voice.user_transcript.chars"])
        assertEquals(
            "cc9f8ee1eed7d66b232f94d69e0e81d2107343d391e604688c4d636657a61136",
            attributes["voice.user_transcript.sha256"],
        )
    }

    @Test
    fun `voice text payload does not mark short text as truncated`() {
        val attributes = voiceTextPayload(
            key = "answer",
            text = "done",
        )

        assertEquals("done", attributes["answer"])
        assertEquals(false, attributes["answer.truncated"])
        assertEquals(4, attributes["answer.chars"])
        assertTrue(attributes["answer.sha256"].toString().isNotBlank())
    }
}
