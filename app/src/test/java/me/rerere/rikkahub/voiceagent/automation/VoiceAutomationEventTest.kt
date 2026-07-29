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
                "\"playbackEpoch\":1,\"byteCount\":3200,\"succeeded\":true," +
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

    private fun event(
        monotonicMs: Long = 10,
        wallClockMs: Long = 20,
        runHash: String = RUN_HASH,
        correlationKind: VoiceAutomationCorrelationKind? = null,
        byteCount: Long? = null,
    ) = VoiceAutomationEvent(
        monotonicMs = monotonicMs,
        wallClockMs = wallClockMs,
        runHash = runHash,
        comparisonHash = COMPARISON_HASH,
        requestedTransport = VoiceAgentTransport.DirectGemini,
        observedTransport = null,
        name = VoiceAutomationEventName.RUN_PREPARED,
        byteCount = byteCount,
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
