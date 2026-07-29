package me.rerere.rikkahub.voiceagent.livekit

import java.nio.ByteBuffer
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.automation.DefaultVoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationCorrelationKind
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationMediaOwner
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
    fun `byte progress is throttled to 50 milliseconds and state changes flush immediately`() {
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

        assertEquals(0, first.position())
        assertEquals(
            listOf(
                Written(byteCount = 2, nonSilent = true),
                Written(byteCount = 6, nonSilent = true),
                Written(byteCount = 2, nonSilent = false),
            ),
            recording.written,
        )
        assertEquals(1, recording.silenceConfirmations)
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
    fun `close drains once and rejects every later remote frame`() {
        val recording = RecordingAudioProbe()
        val probe = LiveKitRemoteAudioProbe(recording, monotonicMs = { 1L })
        probe.onData(ByteBuffer.wrap(byteArrayOf(1, 0)), 16, 48_000, 1, 1, 1)

        probe.close()
        val callsAfterClose = recording.totalCalls()
        probe.close()
        probe.onData(ByteBuffer.wrap(byteArrayOf(2, 0)), 16, 48_000, 1, 1, 2)

        assertEquals(1, recording.drained)
        assertEquals(callsAfterClose, recording.totalCalls())
    }

    private data class Written(
        val byteCount: Int,
        val nonSilent: Boolean,
    )

    private class RecordingAudioProbe : VoiceAutomationAudioProbe {
        val written = mutableListOf<Written>()
        var silenceConfirmations = 0
        var drained = 0

        override fun onInjectionStarted(totalBytes: Long) = Unit
        override fun onInjectionChunk(byteCount: Int) = Unit
        override fun onInjectionCompleted() = Unit
        override fun onOutputQueued(byteCount: Int) = Unit
        override fun captureLiveKitMediaOwner() = VoiceAutomationMediaOwner(RUN_HASH)

        override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) {
            written += Written(byteCount, nonSilent)
        }

        override fun onOutputDrained() {
            drained += 1
        }

        override fun onInterruptionStarted() = Unit

        override fun onOutputSilenceConfirmed() {
            silenceConfirmations += 1
        }

        fun totalCalls(): Int = written.size + silenceConfirmations + drained
    }

    private class FakeClock {
        private var currentMs = 1L

        fun nowMs(): Long = currentMs

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
    }
}
