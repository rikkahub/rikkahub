package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.SynchronizedVoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Actor-shell suite for [HermesAnnouncer]. The pure sequencing (which effect in which
 * state, in which order) is covered by the reducer's own transition tests; this suite
 * proves the shell — bridge registry, replay, effect execution against a real
 * [HermesQueueStore], failure containment, and the quiet/hold/pacing timers driven by
 * virtual time.
 */
class HermesAnnouncerTest {
    private val writer = HermesToolRecordWriter()
    private val transcriptPersister = VoiceTranscriptPersister()

    // 1
    @Test
    fun `enqueueCompletion sends the completion follow-up and marks the record announced`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store, telemetry = telemetry(diagnostics))

        announcer.attachScoped(bridge, sessionId = 7L)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        runCurrent()

        assertEquals(listOf("call-1"), bridge.completions)
        assertTrue(store.latestRecord(callId = "call-1", jobId = "job-1")!!.resultAnnounced)
    }

    // 2
    @Test
    fun `completion send returning false appends the visible text fallback`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge(completionResult = false)
        val announcer = announcer(queueStore = store)

        // The unannounced Complete record is replayed on attach; its send returns false,
        // so the reducer's SendReturned(Failed) drives the visible-text fallback.
        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()

        val record = store.latestRecord(callId = "call-1", jobId = "job-1")!!
        assertFalse(record.resultAnnounced)
        assertTrue(record.messageWritten)
    }

    // 3
    @Test
    fun `already-announced record is skipped without a send`() = runTest {
        val store = store(
            emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer", announced = true),
        )
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        announcer.attachScoped(bridge, sessionId = 7L)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        runCurrent()

        assertTrue(bridge.completions.isEmpty())
        assertFalse(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
    }

    // 4
    @Test
    fun `attachScoped replays unannounced terminal results`() = runTest {
        val store = store(
            emptyConversation()
                .withComplete(callId = "call-complete", jobId = "job-c", answer = "done")
                .withFailed(callId = "call-failed", jobId = "job-f", message = "boom"),
        )
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        // Both terminal records replay as intents; the completion goes first, then
        // generation-complete pacing releases the terminal follow-up.
        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()
        assertEquals(listOf("call-complete"), bridge.completions)

        announcer.onGenerationComplete()
        runCurrent()

        assertEquals(listOf("call-complete"), bridge.completions)
        assertEquals(listOf("call-failed"), bridge.terminals)
    }

    // 5
    @Test
    fun `still-working send marks stillWorkingAnnounced and records the diagnostic`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val store = store(emptyConversation().withRunning(callId = "call-run", jobId = "job-run"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store, telemetry = telemetry(diagnostics))

        announcer.attachScoped(bridge, sessionId = 7L)
        announcer.enqueueStillWorking(callId = "call-run", jobId = "job-run")
        runCurrent()

        assertEquals(listOf("call-run"), bridge.stillWorking)
        assertTrue(store.latestRecord(callId = "call-run", jobId = "job-run")!!.stillWorkingAnnounced)
        assertTrue(diagnostics.any { it.first == "hermes_still_working_announced" })
    }

    // 6
    @Test
    fun `detachScoped with no default drains queued intents to text fallback`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        // Audio active before the completion is enqueued, so it holds instead of sending;
        // detaching with no default then drains it to the visible-text fallback.
        announcer.onAssistantAudioActive(active = true)
        announcer.attachScoped(bridge, sessionId = 7L)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        announcer.detachScoped(bridge)
        runCurrent()

        assertTrue(bridge.completions.isEmpty())
        assertTrue(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
    }

    // 7
    @Test
    fun `detachScoped falls back to the default bridge when no scoped bridge was ever attached`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val scoped = RecordingBridge()
        val default = RecordingBridge()
        val announcer = announcer(queueStore = store, defaultBridge = { default })

        // The unbound session id does not mark scopedBridgeEverAttached, so detaching the
        // scoped bridge re-attaches the default provider, which then owns the send.
        announcer.attachScoped(scoped, sessionId = HermesJobManager.UNBOUND_BRIDGE_SESSION_ID)
        announcer.detachScoped(scoped)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        runCurrent()

        assertEquals(listOf("call-1"), default.completions)
        assertTrue(scoped.completions.isEmpty())
    }

    // 8
    @Test
    fun `detachScoped after a scoped attach does not re-attach the default`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val scoped = RecordingBridge()
        val default = RecordingBridge()
        val announcer = announcer(queueStore = store, defaultBridge = { default })

        announcer.attachScoped(scoped, sessionId = 7L)
        announcer.detachScoped(scoped)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        runCurrent()

        assertTrue(default.completions.isEmpty())
        assertTrue(scoped.completions.isEmpty())
        assertTrue(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
    }

    // 8b (review fix)
    @Test
    fun `detachScoped of a non-current bridge is a no-op`() = runTest {
        val store = store(emptyConversation())
        val bridge1 = RecordingBridge()
        val bridge2 = RecordingBridge()
        val announcer = announcer(queueStore = store)

        announcer.attachScoped(bridge1, sessionId = 7L)
        announcer.attachScoped(bridge2, sessionId = 8L)
        store.persistTerminal(
            callId = "call-1",
            prompt = "prompt-call-1",
            status = VoiceToolRecordStatus.Complete("the answer"),
            jobId = "job-1",
            announced = false,
        )

        // Stale detach of the replaced bridge must not desync the reducer: B2 stays
        // attached and the later completion is sent, not text-fallen-back.
        announcer.detachScoped(bridge1)
        runCurrent()
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        runCurrent()

        assertEquals(listOf("call-1"), bridge2.completions)
        assertTrue(bridge1.completions.isEmpty())
        assertFalse(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
        assertEquals(8L, announcer.currentAttachment()?.sessionId)
        assertSame(bridge2, announcer.currentAttachment()?.bridge)
    }

    // 9
    @Test
    fun `attachDefaultIfNeeded is idempotent and refuses after close`() = runTest {
        val store = store(emptyConversation())
        val default = RecordingBridge()
        val announcer = announcer(queueStore = store, defaultBridge = { default })

        announcer.attachDefaultIfNeeded()
        val first = announcer.currentAttachment()
        announcer.attachDefaultIfNeeded()
        val second = announcer.currentAttachment()
        assertNotNull(first)
        assertSame(first, second)

        announcer.close()
        announcer.attachDefaultIfNeeded()
        assertNull(announcer.currentAttachment())
        runCurrent()
    }

    // 9b (review fix)
    @Test
    fun `attachScoped after close is a no-op`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        announcer.close()
        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()
        assertNull(announcer.currentAttachment())

        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        runCurrent()

        assertTrue(bridge.completions.isEmpty())
        assertTrue(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
    }

    // 10
    @Test
    fun `a throwing store during a Send effect is contained and the queue continues`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        // markResultAnnounced (the first store update) throws for call-1 AFTER its send
        // succeeded: the outcome stays Sent (delivered announcement, no same-cycle text
        // duplicate), the mark failure is contained + logged, and the queue keeps
        // draining — the replayed call-2 still sends.
        val store = throwingStore(
            emptyConversation()
                .withComplete(callId = "call-1", jobId = "job-1", answer = "first")
                .withComplete(callId = "call-2", jobId = "job-2", answer = "second"),
        )
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store, telemetry = telemetry(diagnostics))

        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()

        assertTrue(bridge.completions.contains("call-1"))
        assertTrue(diagnostics.any { it.first == "announcer_mark_failed" })
        val record1 = store.latestRecord(callId = "call-1", jobId = "job-1")!!
        assertFalse(record1.messageWritten)
        assertFalse(record1.resultAnnounced)

        // The Sent outcome engages generation-complete pacing; releasing it proves the
        // queue was never wedged by the contained mark failure.
        announcer.onGenerationComplete()
        runCurrent()
        assertTrue(bridge.completions.contains("call-2"))
        assertTrue(store.latestRecord(callId = "call-2", jobId = "job-2")!!.resultAnnounced)
    }

    // 11
    @Test
    fun `close mid-queue falls back queued completion intents`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        announcer.onAssistantAudioActive(active = true)
        announcer.attachScoped(bridge, sessionId = 7L)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        announcer.close()
        runCurrent()

        assertTrue(bridge.completions.isEmpty())
        assertTrue(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
    }

    // 12
    @Test
    fun `quiet window delays the send until the timer fires`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store, quietWindowMs = 2_000L)

        // Input delta before attach: the replayed completion is held inside the quiet window.
        announcer.onInputTranscriptDelta()
        announcer.attachScoped(bridge, sessionId = 7L)
        advanceTimeBy(1_999)
        assertTrue(bridge.completions.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("call-1"), bridge.completions)
    }

    // 13
    @Test
    fun `hold deadline releases a send blocked by audio`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store, telemetry = telemetry(diagnostics), maxHoldMs = 15_000L)

        announcer.onAssistantAudioActive(active = true)
        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()
        assertTrue(bridge.completions.isEmpty())

        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(listOf("call-1"), bridge.completions)
        assertTrue(diagnostics.any { it.first == "hermes_announcement_released_at_deadline" })
    }

    // 14
    @Test
    fun `generation-complete pacing holds the second announcement`() = runTest {
        val store = store(
            emptyConversation()
                .withComplete(callId = "call-a", jobId = "job-a", answer = "answer a")
                .withComplete(callId = "call-b", jobId = "job-b", answer = "answer b"),
        )
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()
        assertEquals(listOf("call-a"), bridge.completions)

        announcer.onGenerationComplete()
        runCurrent()
        assertEquals(listOf("call-a", "call-b"), bridge.completions)
    }

    // 15 (Task 10 drain barrier)
    @Test
    fun `awaitClosed drains intents enqueued before close before returning`() = runTest {
        val store = store(emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "the answer"))
        val bridge = RecordingBridge()
        val announcer = announcer(queueStore = store)

        // Audio active holds the completion instead of sending it. close()+awaitClosed() must
        // drain the held intent to the visible-text fallback before the barrier returns — no
        // runCurrent() here, awaitClosed() is itself the drain point.
        announcer.onAssistantAudioActive(active = true)
        announcer.attachScoped(bridge, sessionId = 7L)
        announcer.enqueueCompletion(callId = "call-1", jobId = "job-1")
        announcer.close()
        announcer.awaitClosed()

        assertTrue(bridge.completions.isEmpty())
        assertTrue(store.latestRecord(callId = "call-1", jobId = "job-1")!!.messageWritten)
    }

    // 16 (Task 10 review fix: the drain ack is tied to its own marker, not to any Close)
    @Test
    fun `awaitClosed drains tail intents enqueued after close`() = runTest {
        // The store yields inside update(), mirroring the production store's genuine
        // suspension — that is the window in which a barrier released too early lets the
        // scope cancel kill an undrained tail intent.
        val store = yieldingStore(
            emptyConversation().withComplete(callId = "call-1", jobId = "job-1", answer = "first"),
        )
        val bridge = RecordingBridge(completionDelayMs = 10_000L)
        val announcer = announcer(queueStore = store, bridgeSendTimeoutMs = 60_000L)

        // Replay starts the completion send for call-1; the consumer stalls mid-send.
        announcer.attachScoped(bridge, sessionId = 7L)
        runCurrent()
        assertEquals(listOf("call-1"), bridge.completions)

        // A job finishes while the consumer is stalled (like a job actor completing during
        // awaitJobs()): terminal record persisted, close() posted (Close A), THEN the tail
        // intent enqueued — after Close A.
        store.persistTerminal(
            callId = "call-2",
            prompt = "prompt-call-2",
            status = VoiceToolRecordStatus.Complete("second"),
            jobId = "job-2",
            announced = false,
        )
        announcer.close()
        announcer.enqueueCompletion(callId = "call-2", jobId = "job-2")

        // The barrier, then the scope cancel it exists to make safe, exactly at release.
        val barrier = launch {
            announcer.awaitClosed()
            announcer.consumer?.cancel() // simulates hermesScope.cancel() right after the barrier
        }
        runCurrent() // register the drain ack while the consumer is still stalled

        advanceTimeBy(10_001)
        barrier.join()

        // call-2 must have been fallback-processed BEFORE the barrier released. Pre-fix
        // (ack completed on ANY Close), Close A released the barrier while call-2 was
        // still queued and the cancel killed it undrained — this assertion goes red.
        assertTrue(store.latestRecord(callId = "call-2", jobId = "job-2")!!.messageWritten)
    }

    // --- fixtures ---

    private fun emptyConversation(): Conversation = Conversation.ofId(Uuid.random())

    private fun Conversation.withComplete(
        callId: String,
        jobId: String?,
        answer: String,
        prompt: String = "prompt-$callId",
        announced: Boolean = false,
    ): Conversation = writer.upsertHermesTool(
        conversation = this,
        callId = callId,
        prompt = prompt,
        status = VoiceToolRecordStatus.Complete(answer),
        jobId = jobId,
        announceOnWrite = announced,
    )

    private fun Conversation.withFailed(
        callId: String,
        jobId: String?,
        message: String,
        prompt: String = "prompt-$callId",
    ): Conversation = writer.upsertHermesTool(
        conversation = this,
        callId = callId,
        prompt = prompt,
        status = VoiceToolRecordStatus.Failed(message),
        jobId = jobId,
    )

    private fun Conversation.withRunning(
        callId: String,
        jobId: String?,
        prompt: String = "prompt-$callId",
    ): Conversation = writer.upsertHermesTool(
        conversation = this,
        callId = callId,
        prompt = prompt,
        status = VoiceToolRecordStatus.Running,
        jobId = jobId,
    )

    private fun store(conversation: Conversation): HermesQueueStore = HermesQueueStore(
        conversationStore = SynchronizedVoiceConversationStore(InMemoryVoiceConversationStore(conversation)),
        writer = writer,
        transcriptPersister = transcriptPersister,
    )

    private fun throwingStore(conversation: Conversation): HermesQueueStore = HermesQueueStore(
        conversationStore = SynchronizedVoiceConversationStore(
            FirstUpdateThrowsStore(InMemoryVoiceConversationStore(conversation)),
        ),
        writer = writer,
        transcriptPersister = transcriptPersister,
    )

    private fun yieldingStore(conversation: Conversation): HermesQueueStore = HermesQueueStore(
        conversationStore = SynchronizedVoiceConversationStore(
            YieldingStore(InMemoryVoiceConversationStore(conversation)),
        ),
        writer = writer,
        transcriptPersister = transcriptPersister,
    )

    private fun telemetry(
        diagnostics: MutableList<Pair<String, String>> = mutableListOf(),
    ): HermesJobTelemetry = HermesJobTelemetry(
        observability = NoOpVoiceObservability,
        traceContext = VoiceTraceContext(traceId = "trace-test", voiceSessionId = "session-test"),
        recordDiagnostic = { name, detail -> diagnostics += name to detail },
        writeQueueEvent = {},
        writeHermesAnswer = {},
        onJobCompleted = {},
        onJobFailed = {},
        onPollFailed = {},
    )

    private fun TestScope.announcer(
        queueStore: HermesQueueStore,
        telemetry: HermesJobTelemetry = telemetry(),
        defaultBridge: (() -> HermesSessionBridge)? = null,
        bridgeSendTimeoutMs: Long = HermesAnnouncer.DEFAULT_BRIDGE_SEND_TIMEOUT_MS,
        quietWindowMs: Long = 0L,
        maxHoldMs: Long = HermesAnnouncer.DEFAULT_MAX_HOLD_MS,
    ): HermesAnnouncer = HermesAnnouncer(
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        queueStore = queueStore,
        telemetry = telemetry,
        defaultBridge = defaultBridge,
        bridgeSendTimeoutMs = bridgeSendTimeoutMs,
        quietWindowMs = quietWindowMs,
        maxHoldMs = maxHoldMs,
        nowMs = { testScheduler.currentTime },
    )

    /** A conversation store whose [update] suspends (yields) first, like the production store. */
    private class YieldingStore(
        private val delegate: VoiceConversationStore,
    ) : VoiceConversationStore {
        override val conversation: StateFlow<Conversation> get() = delegate.conversation

        override suspend fun update(transform: (Conversation) -> Conversation) {
            yield()
            delegate.update(transform)
        }
    }

    /** A conversation store whose first [update] throws, then delegates normally. */
    private class FirstUpdateThrowsStore(
        private val delegate: VoiceConversationStore,
    ) : VoiceConversationStore {
        private var thrown = false
        override val conversation: StateFlow<Conversation> get() = delegate.conversation

        override suspend fun update(transform: (Conversation) -> Conversation) {
            if (!thrown) {
                thrown = true
                throw IllegalStateException("mark boom")
            }
            delegate.update(transform)
        }
    }

    private class RecordingBridge(
        var completionResult: Boolean = true,
        var terminalResult: Boolean = true,
        var stillWorkingResult: Boolean = true,
        var completionDelayMs: Long = 0L,
    ) : HermesSessionBridge {
        val completions = mutableListOf<String>()
        val terminals = mutableListOf<String>()
        val stillWorking = mutableListOf<String>()
        override suspend fun sendQueuedAcknowledgement(callId: String, sessionId: Long) = true
        override suspend fun sendCompletionFollowUp(callId: String, prompt: String, answer: String, sessionId: Long): Boolean {
            completions += callId
            if (completionDelayMs > 0L) delay(completionDelayMs)
            return completionResult
        }
        override suspend fun sendTerminalFollowUp(callId: String, prompt: String, status: HermesQueueStatus, reason: String, sessionId: Long): Boolean {
            terminals += callId; return terminalResult
        }
        override suspend fun sendStillWorkingUpdate(callId: String, prompt: String, sessionId: Long): Boolean {
            stillWorking += callId; return stillWorkingResult
        }
        override suspend fun sendCancelResponse(callId: String, outcome: CancelHermesOutcome, sessionId: Long) = true
    }
}
