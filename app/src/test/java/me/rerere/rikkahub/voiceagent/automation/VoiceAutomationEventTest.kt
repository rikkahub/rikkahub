package me.rerere.rikkahub.voiceagent.automation

import java.nio.file.Files
import me.rerere.rikkahub.voiceagent.VoiceAgentCallEndpointType
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAutomationEventTest {
    @Test
    fun `append writes one canonical sanitized json line`() {
        val root = Files.createTempDirectory("voice-automation-events").toFile()
        val writer = VoiceAutomationEventWriter.create(root, RUN_HASH)

        writer.append(
            VoiceAutomationEvent(
                monotonicMs = 10,
                wallClockMs = 20,
                runHash = RUN_HASH,
                comparisonHash = COMPARISON_HASH,
                requestedTransport = VoiceAgentTransport.DirectGemini,
                observedTransport = VoiceAgentTransport.LiveKitExperimental,
                name = VoiceAutomationEventName.CALL_ACTIVE,
                route = VoiceAgentCallEndpointType.Speaker,
                network = VoiceAutomationNetwork.WIFI,
                lifecycle = VoiceAutomationLifecycle.FOREGROUND,
                playbackEpoch = 1,
                byteCount = 3_200,
                succeeded = true,
                correlationKind = VoiceAutomationCorrelationKind.SESSION,
                correlationHash = CORRELATION_HASH,
            ),
        )

        val line = writer.file.readText()

        assertEquals(1, line.lines().filter(String::isNotEmpty).size)
        assertEquals(
            "{\"schemaVersion\":1,\"monotonicMs\":10,\"wallClockMs\":20," +
                "\"runHash\":\"$RUN_HASH\",\"comparisonHash\":\"$COMPARISON_HASH\"," +
                "\"requestedTransport\":\"direct_gemini\"," +
                "\"observedTransport\":\"livekit_experimental\",\"name\":\"call_active\"," +
                "\"route\":\"Speaker\",\"network\":\"wifi\",\"lifecycle\":\"foreground\"," +
                "\"playbackEpoch\":1,\"byteCount\":3200,\"rmsActive\":null," +
                "\"audioWindowMicros\":null,\"succeeded\":true," +
                "\"reconnect_duration_ms\":null,\"failure_category\":null," +
                "\"failure_message\":null," +
                "\"correlationKind\":\"session\",\"correlationHash\":\"$CORRELATION_HASH\"," +
                "\"requestedModelHash\":null,\"observedModelHash\":null,\"voiceHash\":null," +
                "\"instructionHash\":null,\"directAccountConfigurationHash\":null," +
                "\"conversationHash\":null,\"captureSource\":null,\"micBytes\":null," +
                "\"fixtureBytes\":null}\n",
            line,
        )
        assertTrue(writer.file.canRead())
        assertTrue(writer.file.canWrite())
    }

    @Test
    fun `LiveKit playback written serializes RMS window fields canonically`() {
        val writer = VoiceAutomationEventWriter.create(
            Files.createTempDirectory("voice-automation-rms").toFile(),
            RUN_HASH,
        )
        writer.append(
            event(
                requestedTransport = VoiceAgentTransport.LiveKitExperimental,
                name = VoiceAutomationEventName.PLAYBACK_WRITTEN,
                playbackEpoch = 1,
                byteCount = 3_200,
                rmsActive = true,
                audioWindowMicros = 20_000,
            ),
        )

        val line = writer.file.readText()
        assertTrue(
            line.contains(
                "\"byteCount\":3200,\"rmsActive\":true," +
                    "\"audioWindowMicros\":20000,\"succeeded\":null",
            ),
        )
    }

    @Test
    fun `RMS window fields are exclusive to LiveKit playback written`() {
        val valid = event(
            requestedTransport = VoiceAgentTransport.LiveKitExperimental,
            name = VoiceAutomationEventName.PLAYBACK_WRITTEN,
            playbackEpoch = 1,
            byteCount = 320,
            rmsActive = false,
            audioWindowMicros = 10_000,
        )
        VoiceAutomationEventValidation.validate(valid)

        listOf(
            valid.copy(rmsActive = null),
            valid.copy(audioWindowMicros = null),
            valid.copy(audioWindowMicros = 0),
            valid.copy(byteCount = 0),
            valid.copy(requestedTransport = VoiceAgentTransport.DirectGemini),
            valid.copy(name = VoiceAutomationEventName.PLAYBACK_ACTIVE),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                VoiceAutomationEventValidation.validate(invalid)
            }
        }
    }

    @Test
    fun `append rejects fields outside the sanitized schema`() {
        val writer = VoiceAutomationEventWriter.create(
            Files.createTempDirectory("voice-automation-invalid").toFile(),
            RUN_HASH,
        )

        assertFailsWith<IllegalArgumentException> {
            writer.append(event(runHash = "raw-run-id"))
        }
        assertFailsWith<IllegalArgumentException> {
            writer.append(event(runHash = COMPARISON_HASH))
        }
        assertFailsWith<IllegalArgumentException> {
            writer.append(event(correlationKind = VoiceAutomationCorrelationKind.PARTICIPANT))
        }
        assertFailsWith<IllegalArgumentException> {
            writer.append(event(byteCount = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            writer.append(event(monotonicMs = 0))
        }
    }

    @Test
    fun `append rejects non monotonic timestamps`() {
        val writer = VoiceAutomationEventWriter.create(
            Files.createTempDirectory("voice-automation-ordering").toFile(),
            RUN_HASH,
        )
        writer.append(event(monotonicMs = 10))

        assertFailsWith<IllegalStateException> {
            writer.append(event(monotonicMs = 10, wallClockMs = 21))
        }
    }

    @Test
    fun `writer recreation rejects an existing non empty run artifact`() {
        val root = Files.createTempDirectory("voice-automation-existing-run").toFile()
        VoiceAutomationEventWriter.create(root, RUN_HASH).append(event())

        assertFailsWith<IllegalStateException> {
            VoiceAutomationEventWriter.create(root, RUN_HASH)
        }
    }

    @Test
    fun `LiveKit reconnect restoration serializes reconnect duration and failure serializes fixed diagnostics`() {
        val writer = VoiceAutomationEventWriter.create(
            Files.createTempDirectory("voice-automation-spec-a-evidence").toFile(),
            RUN_HASH,
        )
        val reconnect = event(
            monotonicMs = 10,
            requestedTransport = VoiceAgentTransport.LiveKitExperimental,
            name = VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED,
            reconnectDurationMs = 5_000,
        )
        val failure = event(
            monotonicMs = 11,
            requestedTransport = VoiceAgentTransport.LiveKitExperimental,
            name = VoiceAutomationEventName.FAILURE,
            succeeded = false,
            failureCategory = "NETWORK_TIMEOUT",
            failureMessage = "LiveKit connection timed out after 20s",
        )

        VoiceAutomationEventValidation.validate(reconnect)
        VoiceAutomationEventValidation.validate(failure)
        assertFailsWith<IllegalArgumentException> {
            VoiceAutomationEventValidation.validate(
                failure.copy(requestedTransport = VoiceAgentTransport.DirectGemini),
            )
        }
        writer.append(reconnect)
        writer.append(failure)
        val serialized = writer.file.readText()
        assertTrue(serialized.contains("\"reconnect_duration_ms\":5000"))
        assertTrue(serialized.contains("\"failure_category\":\"NETWORK_TIMEOUT\""))
    }

    private fun event(
        monotonicMs: Long = 10,
        wallClockMs: Long = 20,
        runHash: String = RUN_HASH,
        requestedTransport: VoiceAgentTransport = VoiceAgentTransport.DirectGemini,
        name: VoiceAutomationEventName = VoiceAutomationEventName.RUN_PREPARED,
        playbackEpoch: Long? = null,
        correlationKind: VoiceAutomationCorrelationKind? = null,
        byteCount: Long? = null,
        rmsActive: Boolean? = null,
        audioWindowMicros: Long? = null,
        succeeded: Boolean? = null,
        reconnectDurationMs: Long? = null,
        failureCategory: String? = null,
        failureMessage: String? = null,
    ) = VoiceAutomationEvent(
        monotonicMs = monotonicMs,
        wallClockMs = wallClockMs,
        runHash = runHash,
        comparisonHash = COMPARISON_HASH,
        requestedTransport = requestedTransport,
        observedTransport = null,
        name = name,
        playbackEpoch = playbackEpoch,
        byteCount = byteCount,
        rmsActive = rmsActive,
        audioWindowMicros = audioWindowMicros,
        succeeded = succeeded,
        reconnectDurationMs = reconnectDurationMs,
        failureCategory = failureCategory,
        failureMessage = failureMessage,
        correlationKind = correlationKind,
        correlationHash = null,
    )

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COMPARISON_HASH = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val CORRELATION_HASH = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
