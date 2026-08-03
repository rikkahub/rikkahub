package me.rerere.rikkahub.voiceagent.livekit

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueuePersistenceResult
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister

internal fun interface VoiceExperienceEvidenceSink {
    suspend fun append(event: LiveKitVoiceExperienceEvent)

    companion object {
        val NoOp = VoiceExperienceEvidenceSink { }
    }
}

internal interface LiveKitPersistenceOwner {
    suspend fun drain()
    fun close()
}

internal class LiveKitVoicePersistenceBridge(
    private val voiceSessionId: String,
    private val agentIdentity: String,
    private val queueStore: HermesQueueStore,
    private val transcriptPersister: VoiceTranscriptPersister,
    private val conversationStore: VoiceConversationStore,
    private val evidence: VoiceExperienceEvidenceSink = VoiceExperienceEvidenceSink.NoOp,
    private val now: () -> Instant = Instant::now,
) : LiveKitPersistenceOwner {
    private val mutex = Mutex()
    private val persistedEventPayloadHashes = mutableMapOf<String, String>()
    private val persistedJobCorrelations =
        mutableMapOf<Pair<String, String>, LiveKitJobCorrelation>()
    private val closed = AtomicBoolean(false)

    suspend fun handle(callerIdentity: String, payload: String): String = mutex.withLock {
        require(!closed.get()) { "LiveKit persistence bridge is closed" }
        require(callerIdentity == agentIdentity) { "Unexpected LiveKit RPC caller" }
        val event = requireNotNull(parseLiveKitVoiceExperienceEvent(payload)) {
            "Invalid LiveKit persistence event"
        }
        require(event.voiceSessionId == voiceSessionId) {
            "Unexpected LiveKit voice session"
        }
        val jobIdentityAndCorrelation = when (event) {
            is LiveKitVoiceExperienceEvent.JobAccepted ->
                (event.toolCallId to event.jobId) to event.correlation()

            is LiveKitVoiceExperienceEvent.JobState ->
                (event.toolCallId to event.jobId) to event.correlation()

            else -> null
        }
        jobIdentityAndCorrelation?.let { (jobIdentity, correlation) ->
            persistedJobCorrelations[jobIdentity]?.let { persistedCorrelation ->
                require(persistedCorrelation == correlation) {
                    "LiveKit job correlation changed"
                }
            }
        }
        val payloadHash = voiceSha256(payload)
        val persistedPayloadHash = persistedEventPayloadHashes[event.eventId]
        if (persistedPayloadHash != null) {
            require(persistedPayloadHash == payloadHash) {
                "LiveKit persistence event ID collision"
            }
        } else {
            persist(event)
            evidence.append(event)
            persistedEventPayloadHashes[event.eventId] = payloadHash
            if (event is LiveKitVoiceExperienceEvent.JobAccepted) {
                persistedJobCorrelations[event.toolCallId to event.jobId] = event.correlation()
            }
        }
        LiveKitPersistenceAck(
            version = 1,
            voiceSessionId = voiceSessionId,
            eventId = event.eventId,
            status = "persisted",
            persistedAt = now().toString(),
        ).canonicalJson()
    }

    override suspend fun drain() {
        mutex.withLock { }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            conversationStore.close()
        }
    }

    private suspend fun persist(event: LiveKitVoiceExperienceEvent) {
        when (event) {
            is LiveKitVoiceExperienceEvent.JobAccepted ->
                queueStore.persistLiveKitAcceptance(
                    callId = event.toolCallId,
                    prompt = event.prompt,
                    jobId = event.jobId,
                    originatingUserTurnId = event.userTurnId,
                    requestHash = event.requestHash,
                    argumentHash = event.argumentHash,
                    producer = HERMES_PRODUCER,
                ).requireNonConflicting("LiveKit Hermes acceptance conflicts with persisted record")

            is LiveKitVoiceExperienceEvent.JobState -> persistJobState(event)
            is LiveKitVoiceExperienceEvent.Transcript -> persistTranscript(event)
            is LiveKitVoiceExperienceEvent.FollowUpCorrelation -> Unit
            is LiveKitVoiceExperienceEvent.Delivery -> when (event.kind) {
                "delivery_announced" ->
                    queueStore.markLiveKitResultAnnounced(
                        callId = event.toolCallId,
                        jobId = event.jobId,
                        assistantTurnId = requireNotNull(event.assistantTurnId),
                        voiceSessionId = voiceSessionId,
                    ).requireNonConflicting(
                        "LiveKit delivery announcement has no matching grounded assistant turn"
                    )

                else -> Unit
            }
        }
    }

    private suspend fun persistJobState(event: LiveKitVoiceExperienceEvent.JobState) {
        when (event.kind) {
            "job_running" -> {
                val prompt = requireMatchingActivePrompt(event)
                queueStore.persistActive(
                    callId = event.toolCallId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Running,
                    jobId = event.jobId,
                    originatingUserTurnId = event.userTurnId,
                    requestHash = event.requestHash,
                    argumentHash = event.argumentHash,
                    producer = HERMES_PRODUCER,
                )
            }

            "still_working" -> {
                val prompt = requireMatchingActivePrompt(event)
                queueStore.persistActive(
                    callId = event.toolCallId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Running,
                    jobId = event.jobId,
                    originatingUserTurnId = event.userTurnId,
                    requestHash = event.requestHash,
                    argumentHash = event.argumentHash,
                    producer = HERMES_PRODUCER,
                )
                queueStore.markStillWorkingAnnounced(
                    callId = event.toolCallId,
                    jobId = event.jobId,
                )
            }

            "job_succeeded" -> persistTerminalState(
                event = event,
                status = VoiceToolRecordStatus.Complete(requireNotNull(event.answer)),
            )

            "job_failed" -> persistFailedState(
                event = event,
                status = VoiceToolRecordStatus.Failed(requireNotNull(event.failureReason)),
            )

            "job_expired" -> persistFailedState(
                event = event,
                status = VoiceToolRecordStatus.Expired(requireNotNull(event.failureReason)),
            )

            "job_canceled" -> persistFailedState(
                event = event,
                status = VoiceToolRecordStatus.Canceled(requireNotNull(event.failureReason)),
            )
        }
    }

    private fun requireMatchingActivePrompt(event: LiveKitVoiceExperienceEvent.JobState): String {
        val existingRecord = queueStore.latestRecord(event.toolCallId, event.jobId)
        if (existingRecord != null) {
            require(existingRecord.originatingUserTurnId == event.userTurnId) {
                "LiveKit Hermes user turn correlation changed"
            }
            require(existingRecord.requestHash == event.requestHash) {
                "LiveKit Hermes request correlation changed"
            }
            require(existingRecord.argumentHash == event.argumentHash) {
                "LiveKit Hermes argument correlation changed"
            }
        }
        return existingRecord?.prompt.orEmpty()
    }

    private suspend fun persistFailedState(
        event: LiveKitVoiceExperienceEvent.JobState,
        status: VoiceToolRecordStatus,
    ) {
        persistTerminalState(event = event, status = status)
    }

    private suspend fun persistTerminalState(
        event: LiveKitVoiceExperienceEvent.JobState,
        status: VoiceToolRecordStatus,
    ) {
        queueStore.persistLiveKitTerminal(
            callId = event.toolCallId,
            status = status,
            jobId = event.jobId,
            originatingUserTurnId = event.userTurnId,
            requestHash = event.requestHash,
            argumentHash = event.argumentHash,
            resultHash = event.resultHash,
            producer = HERMES_PRODUCER,
        ).requireNonConflicting("LiveKit Hermes terminal state conflicts with persisted record")
    }

    private suspend fun persistTranscript(event: LiveKitVoiceExperienceEvent.Transcript) {
        if (event.groundedJobId != null) {
            require(
                queueStore.records().any { record ->
                    record.jobId == event.groundedJobId &&
                        record.status == HermesQueueStatus.Complete &&
                        record.resultHash == event.groundedResultHash
                }
            ) { "LiveKit grounded Hermes result does not match" }
        }
        conversationStore.update { conversation ->
            when (event.role) {
                "user" -> transcriptPersister.upsertUserTranscriptTurn(
                    conversation = conversation,
                    text = event.text,
                    turnId = event.turnId,
                    sessionId = voiceSessionId,
                )

                "assistant" -> transcriptPersister.upsertAssistantTranscriptTurn(
                    conversation = conversation,
                    text = event.text,
                    interrupted = event.interrupted,
                    turnId = event.turnId,
                    sessionId = voiceSessionId,
                    groundedJobId = event.groundedJobId,
                    groundedResultHash = event.groundedResultHash,
                )

                else -> conversation
            }
        }
    }

    private fun HermesQueuePersistenceResult.requireNonConflicting(message: String) {
        require(this != HermesQueuePersistenceResult.Conflict) { message }
    }
}
