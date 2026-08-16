package me.rerere.rikkahub.voiceagent.livekit

import java.nio.ByteBuffer
import java.nio.file.Files
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.automation.DefaultVoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationClock
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationCorrelationKind
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationMediaOwner
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationNetwork
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunBinding
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationScheduledTransition
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationTransitionScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitRemoteAudioProbeTest {
    @Test
    fun `durable progress uses 250 millisecond windows without flushing noisy state changes`() {
        val clock = FakeClock()
        val recording = RecordingAudioProbe()
        val probe = LiveKitRemoteAudioProbe(
            automationAudioProbe = recording,
            monotonicMs = clock::nowMs,
        )
        val first = ByteBuffer.wrap(byteArrayOf(1, 0))

        probe.onData(first, 16, 48_000, 1, 1, 1)
        clock.advanceBy(20)
        probe.onData(ByteBuffer.wrap(byteArrayOf(2, 0)), 16, 48_000, 1, 1, 2)
        clock.advanceBy(29)
        probe.onData(ByteBuffer.wrap(byteArrayOf(3, 0)), 16, 48_000, 1, 1, 3)
        clock.advanceBy(1)
        probe.onData(ByteBuffer.wrap(byteArrayOf(4, 0)), 16, 48_000, 1, 1, 4)
        clock.advanceBy(1)
        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 5)
        probe.close()

        assertEquals(0, first.position())
        assertEquals(
            listOf(
                Written(2, true, false, 20),
                Written(8, true, false, 80),
            ),
            recording.written,
        )
        assertEquals(1, recording.silenceConfirmations)
    }

    @Test
    fun `flush ORs RMS activity sums duration and resets every aggregate`() {
        val clock = FakeClock()
        val recording = RecordingAudioProbe()
        val probe = LiveKitRemoteAudioProbe(recording, clock::nowMs)

        probe.onData(pcm16(255, 480), 16, 48_000, 1, 480, 1)
        clock.advanceBy(1)
        probe.onData(pcm16(255, 480), 16, 48_000, 1, 480, 2)
        clock.advanceBy(1)
        probe.onData(pcm16(256, 480), 16, 48_000, 1, 480, 3)
        clock.advanceBy(248)
        probe.onData(pcm16(255, 480), 16, 48_000, 1, 480, 4)
        clock.advanceBy(1)
        probe.onData(pcm16(255, 480), 16, 48_000, 1, 480, 5)
        probe.close()

        assertEquals(
            listOf(
                Written(960, true, false, 10_000),
                Written(2_880, true, true, 30_000),
                Written(960, true, false, 10_000),
            ),
            recording.written,
        )
    }

    @Test
    fun `round 26 sized noisy stream stays bounded and finalizes with lossless boundaries`() {
        val root = Files.createTempDirectory("livekit-playback-coalescing").toFile()
        try {
            val clock = FakeClock()
            val scheduler = FakeScheduler(clock)
            val runtime = me.rerere.rikkahub.voiceagent.automation.DefaultVoiceAutomationRuntime(
                noBackupFilesDir = root,
                clock = clock,
            )
            runtime.prepare(
                VoiceAutomationRunBinding(
                    RUN_HASH,
                    COMPARISON_HASH,
                    VoiceAgentTransport.LiveKitExperimental,
                ),
            )
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED))
            assertTrue(runtime.markReconnectTransportRestored(RUN_HASH))
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.HANDOVER_STARTED))
            runtime.record(
                VoiceAutomationEventInput(
                    VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED,
                    network = VoiceAutomationNetwork.CELLULAR,
                ),
            )
            runtime.record(
                VoiceAutomationEventInput(
                    VoiceAutomationEventName.HANDOVER_WIFI_RESTORED,
                    network = VoiceAutomationNetwork.WIFI,
                ),
            )
            val sharedProbe = DefaultVoiceAutomationAudioProbe(
                runtimeProvider = { runtime },
                monotonicMs = clock::nowMs,
                scheduler = scheduler,
            )
            val probe = LiveKitRemoteAudioProbe(sharedProbe, clock::nowMs)

            repeat(ROUND_26_PLAYBACK_FRAME_COUNT) { index ->
                val frame = if (index % 2 == 0) {
                    byteArrayOf(1, 0)
                } else {
                    byteArrayOf(0, 0)
                }
                probe.onData(ByteBuffer.wrap(frame), 16, 48_000, 1, 1, index.toLong())
                scheduler.advanceBy(10)
            }
            probe.close()
            runtime.record(
                VoiceAutomationEventInput(
                    VoiceAutomationEventName.CALL_STOPPED,
                    succeeded = true,
                ),
            )

            val artifact = runtime.finalizeRun()
            val lines = artifact.readLines()
            val playbackWrittenCount = lines.count { "\"name\":\"playback_written\"" in it }

            assertTrue(
                "Expected at most $MAX_BOUNDED_PLAYBACK_ROWS playback rows, got $playbackWrittenCount",
                playbackWrittenCount <= MAX_BOUNDED_PLAYBACK_ROWS,
            )
            assertTrue(
                "Expected at least 4x headroom under 16 MiB, got ${artifact.length()} bytes",
                artifact.length() <= MAX_BOUNDED_ARTIFACT_BYTES,
            )
            assertEquals(1, lines.count { "\"name\":\"handover_media_restored\"" in it })
            assertEquals(1, lines.count { "\"name\":\"reconnect_media_restored\"" in it })
            assertTrue("\"name\":\"call_stopped\"" in lines[lines.lastIndex - 1])
            assertTrue("\"name\":\"run_finalized\"" in lines.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `RMS 255 is inactive and RMS 256 is active`() {
        val recording = RecordingAudioProbe()
        val probe = LiveKitRemoteAudioProbe(recording, monotonicMs = { 1L })

        probe.onData(pcm16(255, 1), 16, 48_000, 1, 1, 1)
        probe.onData(pcm16(256, 1), 16, 48_000, 1, 1, 2)
        probe.close()

        assertEquals(listOf(false, true), recording.written.map(Written::rmsActive))
    }

    @Test
    fun `invalid PCM metadata emits no RMS evidence`() {
        val recording = RecordingAudioProbe()
        val probe = LiveKitRemoteAudioProbe(recording, monotonicMs = { 1L })

        probe.onData(ByteBuffer.allocate(0), 16, 48_000, 1, 0, 1)
        probe.onData(pcm16(256, 1), 8, 48_000, 1, 1, 2)
        probe.onData(pcm16(256, 1), 16, 0, 1, 1, 3)
        probe.onData(pcm16(256, 1), 16, 48_000, 1, 0, 4)
        probe.close()

        assertEquals(emptyList<Written>(), recording.written)
    }

    @Test
    fun `remote frames drive first audio dropout and resumption through the shared state machine`() {
        val runtime = RecordingRuntime()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val sharedProbe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
            scheduler = scheduler,
        )
        val probe = LiveKitRemoteAudioProbe(sharedProbe, clock::nowMs)

        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 1)
        assertEquals(0, runtime.events.count { it.name == VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT })

        clock.advanceBy(1)
        probe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 2)
        scheduler.advanceBy(249)
        assertEquals(0, runtime.events.count { it.name == VoiceAutomationEventName.DROPOUT_STARTED })
        scheduler.advanceBy(1)
        assertEquals(1, runtime.events.count { it.name == VoiceAutomationEventName.DROPOUT_STARTED })

        probe.onData(ByteBuffer.wrap(byteArrayOf(2, 0)), 16, 48_000, 1, 1, 3)

        assertEquals(1, runtime.events.count { it.name == VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT })
        assertEquals(1, runtime.events.count { it.name == VoiceAutomationEventName.DROPOUT_ENDED })
        assertEquals(2L, runtime.events.last().playbackEpoch)
        assertEquals(
            listOf(
                MEDIA_STATE_EPOCH_1_HASH,
                MEDIA_STATE_EPOCH_2_HASH,
            ),
            runtime.events
                .filter { it.correlationKind == VoiceAutomationCorrelationKind.MEDIA_STATE }
                .map(VoiceAutomationEventInput::correlationHash),
        )
    }

    @Test
    fun `silent remote frames confirm an interruption only after 100 milliseconds`() {
        val runtime = RecordingRuntime()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val sharedProbe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
            scheduler = scheduler,
        )
        val probe = LiveKitRemoteAudioProbe(sharedProbe, clock::nowMs)
        probe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 1)
        sharedProbe.onInterruptionStarted()

        clock.advanceBy(99)
        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 2)
        assertEquals(0, runtime.events.count { it.name == VoiceAutomationEventName.PLAYBACK_STOPPED })

        clock.advanceBy(1)
        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 3)
        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 4)

        assertEquals(1, runtime.events.count { it.name == VoiceAutomationEventName.PLAYBACK_STOPPED })
    }

    @Test
    fun `stale remote silence and close cannot stop or drain replacement run playback`() {
        val runtime = RecordingRuntime()
        val clock = FakeClock()
        val sharedProbe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
        )
        val staleProbe = LiveKitRemoteAudioProbe(sharedProbe, clock::nowMs)
        staleProbe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 1)

        runtime.runHash = RUN_HASH_B
        val currentProbe = LiveKitRemoteAudioProbe(sharedProbe, clock::nowMs)
        currentProbe.onData(ByteBuffer.wrap(byteArrayOf(2, 0)), 16, 48_000, 1, 1, 2)
        sharedProbe.onInterruptionStarted()
        val replacementEventCount = runtime.events.size

        clock.advanceBy(100)
        staleProbe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 3)
        staleProbe.close()

        assertEquals(replacementEventCount, runtime.events.size)
        assertEquals(0, runtime.events.count { it.name == VoiceAutomationEventName.PLAYBACK_STOPPED })
        assertEquals(0, runtime.events.count { it.name == VoiceAutomationEventName.PLAYBACK_DRAINED })
    }

    @Test
    fun `ownerless LiveKit callbacks cannot mutate a Direct automation run`() {
        val runtime = RecordingRuntime(
            requestedTransport = VoiceAgentTransport.DirectGemini,
        )
        val sharedProbe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = { 1L },
        )
        val probe = LiveKitRemoteAudioProbe(sharedProbe, monotonicMs = { 1L })

        probe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 1)
        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 2)
        probe.close()

        assertEquals(emptyList<VoiceAutomationEventInput>(), runtime.events)
    }

    @Test
    fun `ownerless probe stays inert after a replacement LiveKit run becomes active`() {
        val runtime = RecordingRuntime(
            requestedTransport = VoiceAgentTransport.DirectGemini,
        )
        val sharedProbe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = { 1L },
        )
        val probe = LiveKitRemoteAudioProbe(sharedProbe, monotonicMs = { 1L })
        runtime.runHash = RUN_HASH_B
        runtime.requestedTransport = VoiceAgentTransport.LiveKitExperimental

        probe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 1)
        probe.onData(ByteBuffer.wrap(byteArrayOf(0, 0)), 16, 48_000, 1, 1, 2)
        probe.close()

        assertEquals(emptyList<VoiceAutomationEventInput>(), runtime.events)
    }

    @Test
    fun `track lifecycle records once and close never drains`() {
        val recording = RecordingAudioProbe()
        val probe = LiveKitRemoteAudioProbe(recording, monotonicMs = { 1L })
        probe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 1)

        probe.onTrackAttached()
        probe.onTrackAttached()
        probe.onTrackDetached()
        probe.onTrackDetached()
        probe.close()
        probe.close()

        assertEquals(1, recording.trackAttached)
        assertEquals(1, recording.trackDetached)
        assertEquals(0, recording.drained)
    }

    private data class Written(
        val byteCount: Int,
        val nonSilent: Boolean,
        val rmsActive: Boolean,
        val audioWindowMicros: Long,
    )

    private class RecordingAudioProbe : VoiceAutomationAudioProbe {
        val written = mutableListOf<Written>()
        var silenceConfirmations = 0
        var drained = 0
        var trackAttached = 0
        var trackDetached = 0

        override fun onInjectionStarted(totalBytes: Long) = Unit
        override fun onInjectionChunk(byteCount: Int) = Unit
        override fun onInjectionCompleted() = Unit
        override fun onOutputQueued(byteCount: Int) = Unit
        override fun captureLiveKitMediaOwner() = VoiceAutomationMediaOwner(RUN_HASH)

        override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) {
            error("LiveKit probe must supply RMS window evidence")
        }

        override fun onLiveKitOutputWritten(
            owner: VoiceAutomationMediaOwner,
            byteCount: Int,
            nonSilent: Boolean,
            rmsActive: Boolean,
            audioWindowMicros: Long,
        ) {
            written += Written(byteCount, nonSilent, rmsActive, audioWindowMicros)
        }

        override fun onOutputDrained() {
            drained += 1
        }

        override fun onLiveKitRemoteTrackAttached(owner: VoiceAutomationMediaOwner) {
            trackAttached += 1
        }

        override fun onLiveKitRemoteTrackDetached(owner: VoiceAutomationMediaOwner) {
            trackDetached += 1
        }

        override fun onInterruptionStarted() = Unit

        override fun onOutputSilenceConfirmed() {
            silenceConfirmations += 1
        }

    }

    private fun pcm16(sample: Int, frames: Int): ByteBuffer {
        val bytes = ByteArray(frames * 2)
        repeat(frames) { index ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        return ByteBuffer.wrap(bytes)
    }

    private class FakeClock : VoiceAutomationClock {
        private var currentMs = 1L

        fun nowMs(): Long = currentMs

        override fun monotonicMs(): Long = currentMs

        override fun wallClockMs(): Long = 1_000_000L + currentMs

        fun advanceBy(durationMs: Long) {
            currentMs += durationMs
        }
    }

    private class FakeScheduler(
        private val clock: FakeClock,
    ) : VoiceAutomationTransitionScheduler {
        private val tasks = mutableListOf<ScheduledTask>()

        override fun schedule(
            delayMs: Long,
            transition: () -> Unit,
        ): VoiceAutomationScheduledTransition {
            val task = ScheduledTask(clock.nowMs() + delayMs, transition)
            tasks += task
            return VoiceAutomationScheduledTransition {
                task.cancelled = true
            }
        }

        fun advanceBy(durationMs: Long) {
            clock.advanceBy(durationMs)
            while (true) {
                val due = tasks
                    .filter { !it.cancelled && it.deadlineMs <= clock.nowMs() }
                    .minByOrNull(ScheduledTask::deadlineMs)
                    ?: return
                due.cancelled = true
                due.transition()
            }
        }

        private class ScheduledTask(
            val deadlineMs: Long,
            val transition: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class RecordingRuntime(
        var runHash: String = RUN_HASH,
        var requestedTransport: VoiceAgentTransport = VoiceAgentTransport.LiveKitExperimental,
    ) : VoiceAutomationRuntime {
        val events = mutableListOf<VoiceAutomationEventInput>()

        override fun prepare(binding: VoiceAutomationRunBinding) = Unit

        override fun record(event: VoiceAutomationEventInput) {
            events += event
        }

        override fun status() = VoiceAutomationStatus(
            state = VoiceAutomationRunState.Active,
            runHash = runHash,
            comparisonHash = COMPARISON_HASH,
            requestedTransport = requestedTransport,
            eventCount = events.size.toLong() + 1,
        )

        override fun finalizeRun() = error("not used")

        override fun reset() = Unit
    }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RUN_HASH_B = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val COMPARISON_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val MEDIA_STATE_EPOCH_1_HASH =
            "sha256:86514ed998b71abd571da38b70a6e1e3708d725df54af09202793b529b783148"
        const val MEDIA_STATE_EPOCH_2_HASH =
            "sha256:dad4c9b5ee69ff80c451dffb8b56c298fab9a432faead88bb2aef88fa0072fd6"
        const val ROUND_26_PLAYBACK_FRAME_COUNT = 24_848
        const val MAX_BOUNDED_PLAYBACK_ROWS = 1_000
        const val MAX_BOUNDED_ARTIFACT_BYTES = 4L * 1024L * 1024L
    }
}
