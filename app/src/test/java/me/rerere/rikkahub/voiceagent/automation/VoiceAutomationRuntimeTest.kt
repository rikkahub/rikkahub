package me.rerere.rikkahub.voiceagent.automation

import java.nio.file.Files
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceAutomationRuntimeTest {
    @Test
    fun `host network restoration cannot synthesize recovery without transport callback`() {
        val root = Files.createTempDirectory("voice-automation-runtime-no-transport").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        runtime.prepare(
            VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.LiveKitExperimental),
        )

        listOf(
            VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED),
            VoiceAutomationEventInput(VoiceAutomationEventName.HANDOVER_STARTED),
            VoiceAutomationEventInput(
                VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED,
                network = VoiceAutomationNetwork.CELLULAR,
            ),
            VoiceAutomationEventInput(
                VoiceAutomationEventName.HANDOVER_WIFI_RESTORED,
                network = VoiceAutomationNetwork.WIFI,
            ),
            VoiceAutomationEventInput(
                VoiceAutomationEventName.PLAYBACK_WRITTEN,
                playbackEpoch = 2,
                byteCount = 3_200,
                rmsActive = false,
                audioWindowMicros = 10_000,
            ),
        ).forEach { runtime.record(it) }

        val artifact = runtime.finalizeRun().readText()
        assertFalse("handover_media_restored" in artifact)
        assertFalse("reconnect_media_restored" in artifact)
    }

    @Test
    fun `runtime preserves LiveKit RMS window fields`() {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("voice-automation-runtime-rms").toFile(),
            FakeClock(),
        )
        runtime.prepare(
            VoiceAutomationRunBinding(
                RUN_HASH,
                COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )
        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.PLAYBACK_WRITTEN,
                playbackEpoch = 1,
                byteCount = 960,
                rmsActive = true,
                audioWindowMicros = 10_000,
            ),
        )

        val written = runtime.finalizeRun().readLines().single {
            "\"name\":\"playback_written\"" in it
        }
        assertTrue("\"rmsActive\":true" in written)
        assertTrue("\"audioWindowMicros\":10000" in written)
    }

    @Test
    fun `duplicate recovery episode is rejected instead of silently discarded`() {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("voice-automation-runtime-duplicate-recovery").toFile(),
            FakeClock(),
        )
        runtime.prepare(
            VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.LiveKitExperimental),
        )
        runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED))

        assertFailsWith<IllegalStateException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED))
        }
    }

    @Test
    fun `record is inactive until a run is prepared`() {
        val root = Files.createTempDirectory("voice-automation-runtime-inactive").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())

        runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.CALL_START_REQUESTED))

        assertEquals(VoiceAutomationRunState.Idle, runtime.status().state)
        assertFalse(java.io.File(root, "voice-e2e").exists())
    }

    @Test
    fun `prepared run records typed events then finalizes its jsonl artifact`() {
        val root = Files.createTempDirectory("voice-automation-runtime-active").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        val binding = VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini)

        runtime.prepare(binding)
        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.CALL_ACTIVE,
                observedTransport = VoiceAgentTransport.DirectGemini,
                succeeded = true,
            ),
        )
        val artifact = runtime.finalizeRun()

        assertTrue(artifact.isFile)
        assertEquals(3, artifact.readLines().size)
        assertEquals(VoiceAutomationRunState.Finalized, runtime.status().state)
        assertEquals(RUN_HASH, runtime.status().runHash)
        assertEquals(3, runtime.status().eventCount)
    }

    @Test
    fun `bound finalize rejects stale binding and finalizes the exact active binding`() {
        val root = Files.createTempDirectory("voice-automation-runtime-bound-finalize").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        val activeBinding = VoiceAutomationRunBinding(
            RUN_HASH,
            COMPARISON_HASH,
            VoiceAgentTransport.LiveKitExperimental,
        )
        runtime.prepare(activeBinding)
        val staleBinding = activeBinding.copy(runHash = NEXT_RUN_HASH)

        assertNull(runtime.finalizeRunIfMatches(staleBinding))
        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)
        assertEquals(1, runtime.status().eventCount)

        assertNotNull(runtime.finalizeRunIfMatches(activeBinding))
        assertEquals(VoiceAutomationRunState.Finalized, runtime.status().state)
        assertEquals(2, runtime.status().eventCount)
    }

    @Test
    fun `direct reconnect keeps call active evidence but emits app correlation once per run`() {
        val root = Files.createTempDirectory("voice-automation-runtime-direct-reconnect").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        runtime.prepare(
            VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini),
        )
        val callActive = VoiceAutomationEventInput(
            name = VoiceAutomationEventName.CALL_ACTIVE,
            observedTransport = VoiceAgentTransport.DirectGemini,
            correlationKind = VoiceAutomationCorrelationKind.APP,
            correlationHash = RUN_HASH,
        )

        assertTrue(runtime.recordIfActiveRun(RUN_HASH, callActive))
        assertTrue(runtime.recordIfActiveRun(RUN_HASH, callActive))
        val artifact = runtime.finalizeRun()
        val callActiveLines = artifact.readLines().filter { "\"name\":\"call_active\"" in it }

        assertEquals(2, callActiveLines.size)
        assertTrue(callActiveLines.all { "\"observedTransport\":\"direct_gemini\"" in it })
        assertEquals(1, callActiveLines.count { "\"correlationKind\":\"app\"" in it })
        assertEquals(1, callActiveLines.count { "\"correlationKind\":null" in it })
    }

    @Test
    fun `validated handover closes reconnect on the first owned restored media write`() {
        val root = Files.createTempDirectory("voice-automation-runtime-handover").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        runtime.prepare(
            VoiceAutomationRunBinding(
                RUN_HASH,
                COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )

        listOf(
            VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED),
            VoiceAutomationEventInput(VoiceAutomationEventName.HANDOVER_STARTED),
            VoiceAutomationEventInput(
                VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED,
                network = VoiceAutomationNetwork.CELLULAR,
            ),
        ).forEach { event -> assertTrue(runtime.recordIfActiveRun(RUN_HASH, event)) }
        assertTrue(runtime.markReconnectTransportRestored(RUN_HASH))
        assertTrue(
            runtime.recordIfActiveRun(
                RUN_HASH,
                VoiceAutomationEventInput(
                    VoiceAutomationEventName.PLAYBACK_WRITTEN,
                    playbackEpoch = 2,
                    byteCount = 3_200,
                    rmsActive = false,
                    audioWindowMicros = 10_000,
                ),
            ),
        )
        listOf(
            VoiceAutomationEventInput(
                VoiceAutomationEventName.HANDOVER_WIFI_RESTORED,
                network = VoiceAutomationNetwork.WIFI,
            ),
            VoiceAutomationEventInput(
                VoiceAutomationEventName.PLAYBACK_WRITTEN,
                playbackEpoch = 3,
                byteCount = 3_200,
                rmsActive = false,
                audioWindowMicros = 10_000,
            ),
        ).forEach { event -> assertTrue(runtime.recordIfActiveRun(RUN_HASH, event)) }

        val artifact = runtime.finalizeRun()
        val names = artifact.readLines().map { line ->
            Regex("""\"name\":\"([^\"]+)\"""").find(line)!!.groupValues[1]
        }
        assertEquals(
            listOf(
                "run_prepared",
                "reconnect_started",
                "handover_started",
                "handover_cellular_observed",
                "reconnect_transport_restored",
                "playback_written",
                "handover_wifi_restored",
                "playback_written",
                "handover_media_restored",
                "reconnect_media_restored",
                "run_finalized",
            ),
            names,
        )
        assertTrue(
            artifact.readLines().takeLast(3).take(2).all { "\"playbackEpoch\":3" in it },
        )
    }

    @Test
    fun `finalized runtime transactionally prepares a fresh run`() {
        val root = Files.createTempDirectory("voice-automation-runtime-next").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        runtime.prepare(
            VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini),
        )
        runtime.finalizeRun()

        runtime.prepare(
            VoiceAutomationRunBinding(
                NEXT_RUN_HASH,
                NEXT_COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )

        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)
        assertEquals(NEXT_RUN_HASH, runtime.status().runHash)
        assertEquals(NEXT_COMPARISON_HASH, runtime.status().comparisonHash)
        assertEquals(VoiceAgentTransport.LiveKitExperimental, runtime.status().requestedTransport)
        assertEquals(1, runtime.status().eventCount)
    }

    @Test
    fun `failed next prepare preserves finalized state and artifact`() {
        val root = Files.createTempDirectory("voice-automation-runtime-transaction").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        runtime.prepare(
            VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini),
        )
        val finalizedArtifact = runtime.finalizeRun()
        val finalizedStatus = runtime.status()
        val finalizedContent = finalizedArtifact.readText()
        java.io.File(
            root,
            "voice-e2e/${NEXT_RUN_HASH.removePrefix("sha256:")}/automation-events.jsonl",
        ).apply {
            checkNotNull(parentFile).mkdirs()
            writeText("occupied")
        }

        assertFailsWith<IllegalStateException> {
            runtime.prepare(
                VoiceAutomationRunBinding(
                    NEXT_RUN_HASH,
                    NEXT_COMPARISON_HASH,
                    VoiceAgentTransport.LiveKitExperimental,
                ),
            )
        }

        assertEquals(finalizedStatus, runtime.status())
        assertEquals(finalizedContent, finalizedArtifact.readText())
    }

    @Test
    fun `non advancing clock still emits strictly increasing event timestamps`() {
        val root = Files.createTempDirectory("voice-automation-runtime-monotonic").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, ConstantClock())
        runtime.prepare(
            VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini),
        )

        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.LIFECYCLE_REQUESTED,
                lifecycle = VoiceAutomationLifecycle.FOREGROUND,
            ),
        )

        val timestamps = java.io.File(
            root,
            "voice-e2e/${RUN_HASH.removePrefix("sha256:")}/automation-events.jsonl",
        ).readLines().map { line ->
            Regex(""""monotonicMs":(\d+)""").find(line)!!.groupValues[1].toLong()
        }
        assertEquals(listOf(7L, 8L), timestamps)
    }

    @Test
    fun `runtime fails closed for duplicate preparation binding drift and invalid finalization`() {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("voice-automation-runtime-closed").toFile(),
            FakeClock(),
        )
        val binding = VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini)

        assertFailsWith<IllegalStateException> { runtime.finalizeRun() }
        runtime.prepare(binding)
        assertFailsWith<IllegalStateException> {
            runtime.prepare(binding.copy(requestedTransport = VoiceAgentTransport.LiveKitExperimental))
        }
        runtime.finalizeRun()
        assertFailsWith<IllegalStateException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.CALL_STOPPED))
        }
    }

    @Test
    fun `reset cannot reprepare a finalized run hash`() {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("voice-automation-runtime-reprepare").toFile(),
            FakeClock(),
        )
        val binding = VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini)
        runtime.prepare(binding)
        runtime.finalizeRun()
        runtime.reset()

        assertFailsWith<IllegalStateException> { runtime.prepare(binding) }
    }

    @Test
    fun `record rejects forged run prepared boundary`() {
        val runtime = preparedRuntime()

        assertFailsWith<IllegalArgumentException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_PREPARED))
        }
    }

    @Test
    fun `record rejects forged run finalized boundary`() {
        val runtime = preparedRuntime()

        assertFailsWith<IllegalArgumentException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_FINALIZED))
        }
    }

    private fun preparedRuntime(): DefaultVoiceAutomationRuntime = DefaultVoiceAutomationRuntime(
        Files.createTempDirectory("voice-automation-runtime-boundary").toFile(),
        FakeClock(),
    ).also { runtime ->
        runtime.prepare(VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini))
    }

    private class FakeClock : VoiceAutomationClock {
        private var tick = 0L

        override fun monotonicMs(): Long = ++tick

        override fun wallClockMs(): Long = 1_000 + tick
    }

    private class ConstantClock : VoiceAutomationClock {
        override fun monotonicMs(): Long = 7

        override fun wallClockMs(): Long = 1_000
    }

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
        const val NEXT_RUN_HASH = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val NEXT_COMPARISON_HASH =
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
