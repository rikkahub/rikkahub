package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.SynchronizedVoiceConversationStore
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import kotlin.uuid.Uuid

internal enum class FocusedProactiveSendKind { Completion, Terminal, Progress }

internal fun focusedConversationFor(kind: FocusedProactiveSendKind): Conversation {
    val writer = HermesToolRecordWriter()
    val status = when (kind) {
        FocusedProactiveSendKind.Completion -> VoiceToolRecordStatus.Complete("answer")
        FocusedProactiveSendKind.Terminal -> VoiceToolRecordStatus.Failed("failed")
        FocusedProactiveSendKind.Progress -> VoiceToolRecordStatus.Running
    }
    return writer.upsertHermesTool(
        conversation = Conversation.ofId(Uuid.random()),
        callId = "call-admission",
        prompt = "prompt-call-admission",
        status = status,
        jobId = "job-admission",
    )
}

internal fun focusedEmptyConversation(): Conversation = Conversation.ofId(Uuid.random())

internal fun focusedStore(conversation: Conversation): HermesQueueStore = HermesQueueStore(
    conversationStore = SynchronizedVoiceConversationStore(InMemoryVoiceConversationStore(conversation)),
    writer = HermesToolRecordWriter(),
    transcriptPersister = VoiceTranscriptPersister(),
)

internal fun TestScope.focusedAnnouncer(
    queueStore: HermesQueueStore,
    defaultBridge: (() -> HermesSessionBridge)? = null,
    nowMs: () -> Long = { testScheduler.currentTime },
    beforeAnnouncementAdmission: suspend () -> Unit = {},
): HermesAnnouncer = HermesAnnouncer(
    scope = backgroundScope,
    dispatcher = StandardTestDispatcher(testScheduler),
    queueStore = queueStore,
    telemetry = HermesJobTelemetry(
        observability = NoOpVoiceObservability,
        traceContext = VoiceTraceContext(traceId = "trace-test", voiceSessionId = "session-test"),
        recordDiagnostic = { _, _ -> },
        writeQueueEvent = {},
        writeHermesAnswer = {},
        onJobCompleted = {},
        onJobFailed = {},
        onPollFailed = {},
    ),
    defaultBridge = defaultBridge,
    quietWindowMs = 0L,
    nowMs = nowMs,
    beforeAnnouncementAdmission = beforeAnnouncementAdmission,
)

internal fun HermesAnnouncer.prepareFocusedProactiveSend(
    kind: FocusedProactiveSendKind,
    bridge: HermesSessionBridge,
) {
    onGeminiTurnActive()
    attachScoped(bridge, sessionId = 7L)
    if (kind == FocusedProactiveSendKind.Progress) {
        enqueueStillWorking("call-admission", "job-admission")
    }
}

internal fun focusedRegistryLock(announcer: HermesAnnouncer): Any =
    HermesAnnouncer::class.java.getDeclaredField("lock").run {
        isAccessible = true
        requireNotNull(get(announcer))
    }

internal fun focusedAnnouncerState(announcer: HermesAnnouncer): AnnouncerState =
    HermesAnnouncer::class.java.getDeclaredField("state").run {
        isAccessible = true
        get(announcer) as AnnouncerState
    }

internal class FocusedRecordingBridge(
    private val beforeSend: suspend (FocusedProactiveSendKind) -> Unit = {},
) : HermesSessionBridge {
    val completions = mutableListOf<String>()
    val terminals = mutableListOf<String>()
    val stillWorking = mutableListOf<String>()

    override suspend fun sendQueuedAcknowledgement(callId: String, sessionId: Long) = true

    override suspend fun sendCompletionFollowUp(
        callId: String,
        prompt: String,
        answer: String,
        sessionId: Long,
    ): Boolean {
        beforeSend(FocusedProactiveSendKind.Completion)
        completions += callId
        return true
    }

    override suspend fun sendTerminalFollowUp(
        callId: String,
        prompt: String,
        status: HermesQueueStatus,
        reason: String,
        sessionId: Long,
    ): Boolean {
        beforeSend(FocusedProactiveSendKind.Terminal)
        terminals += callId
        return true
    }

    override suspend fun sendStillWorkingUpdate(
        callId: String,
        prompt: String,
        sessionId: Long,
    ): Boolean {
        beforeSend(FocusedProactiveSendKind.Progress)
        stillWorking += callId
        return true
    }

    override suspend fun sendCancelResponse(
        callId: String,
        outcome: CancelHermesOutcome,
        sessionId: Long,
    ) = true

    fun assertEnteredExactlyOnce(kind: FocusedProactiveSendKind) {
        assertEquals(
            if (kind == FocusedProactiveSendKind.Completion) listOf("call-admission") else emptyList(),
            completions,
        )
        assertEquals(
            if (kind == FocusedProactiveSendKind.Terminal) listOf("call-admission") else emptyList(),
            terminals,
        )
        assertEquals(
            if (kind == FocusedProactiveSendKind.Progress) listOf("call-admission") else emptyList(),
            stillWorking,
        )
    }
}
