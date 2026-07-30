package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.persistence.VOICE_EVENT_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_GROUNDED_JOB_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_GROUNDED_RESULT_HASH_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SESSION_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister

internal enum class HermesQueuePersistenceResult {
    Mutated,
    Equivalent,
    Conflict,
}

class HermesQueueStore(
    private val conversationStore: VoiceConversationStore,
    private val writer: HermesToolRecordWriter,
    private val transcriptPersister: VoiceTranscriptPersister,
    private val persistenceSessionId: () -> String? = { null },
) {
    private val updateMutex = Mutex()

    fun records(): List<HermesQueueRecord> =
        conversationStore.conversation.value.hermesQueueRecords()

    /** Latest record with this exact identity — a null [jobId] only matches a null-jobId record. */
    fun latestRecord(callId: String, jobId: String?): HermesQueueRecord? =
        records().lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }

    /** Latest record per durable identity, keeping only non-terminal ones. */
    fun activeRecords(): List<HermesQueueRecord> =
        records().latestByHermesDurableIdentity().filter { !it.status.isTerminal }

    /** Latest record per durable identity, keeping terminal ones whose result was never announced. */
    fun unannouncedTerminalRecords(): List<HermesQueueRecord> =
        records().latestByHermesDurableIdentity().filter { it.status.isTerminal && !it.resultAnnounced }

    /**
     * Latest record for a cancel that found no live actor. With [requireUnsubmitted]
     * the caller knows the cancel was scoped to a specific active key, so only a
     * record that never got a jobId may match; otherwise any record for the call.
     */
    fun latestCancelCandidate(callId: String, requireUnsubmitted: Boolean): HermesQueueRecord? =
        records().lastOrNull { it.callId == callId && (!requireUnsubmitted || it.jobId == null) }

    suspend fun markResultAnnounced(
        callId: String,
        jobId: String?,
    ) {
        update { conversation ->
            writer.markResultAnnounced(
                conversation = conversation,
                callId = callId,
                jobId = jobId,
            )
        }
    }

    suspend fun markStillWorkingAnnounced(callId: String, jobId: String?) {
        update { conversation ->
            writer.markStillWorkingAnnounced(
                conversation = conversation,
                callId = callId,
                jobId = jobId,
            )
        }
    }

    internal suspend fun persistLiveKitAcceptance(
        callId: String,
        prompt: String,
        jobId: String,
        originatingUserTurnId: String,
        requestHash: String,
        argumentHash: String,
        producer: String,
    ): HermesQueuePersistenceResult {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val existing = conversation.hermesQueueRecords()
                .lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }
            when {
                existing == null -> {
                    val updated = writer.upsertHermesTool(
                        conversation = conversation,
                        callId = callId,
                        prompt = prompt,
                        status = VoiceToolRecordStatus.Queued,
                        sessionId = sessionId,
                        jobId = jobId,
                        originatingUserTurnId = originatingUserTurnId,
                        requestHash = requestHash,
                        argumentHash = argumentHash,
                        producer = producer,
                    )
                    updated to HermesQueuePersistenceResult.Mutated
                }

                existing.hasLiveKitProvenance(
                    prompt = prompt,
                    originatingUserTurnId = originatingUserTurnId,
                    requestHash = requestHash,
                    argumentHash = argumentHash,
                    producer = producer,
                ) -> conversation to HermesQueuePersistenceResult.Equivalent

                else -> conversation to HermesQueuePersistenceResult.Conflict
            }
        }
    }

    internal suspend fun persistLiveKitTerminal(
        callId: String,
        status: VoiceToolRecordStatus,
        jobId: String,
        originatingUserTurnId: String,
        requestHash: String,
        argumentHash: String,
        resultHash: String?,
        producer: String,
    ): HermesQueuePersistenceResult {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val existing = conversation.hermesQueueRecords()
                .lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }
            when {
                existing?.status?.isTerminal == true -> {
                    val equivalent = existing.hasLiveKitCorrelation(
                        originatingUserTurnId = originatingUserTurnId,
                        requestHash = requestHash,
                        argumentHash = argumentHash,
                        producer = producer,
                    ) && existing.hasEquivalentTerminal(status = status, resultHash = resultHash)
                    conversation to if (equivalent) {
                        HermesQueuePersistenceResult.Equivalent
                    } else {
                        HermesQueuePersistenceResult.Conflict
                    }
                }

                existing != null && !existing.hasLiveKitCorrelation(
                    originatingUserTurnId = originatingUserTurnId,
                    requestHash = requestHash,
                    argumentHash = argumentHash,
                    producer = producer,
                ) -> conversation to HermesQueuePersistenceResult.Conflict

                else -> {
                    val updated = writer.upsertHermesTool(
                        conversation = conversation,
                        callId = callId,
                        prompt = existing?.prompt.orEmpty(),
                        status = status,
                        sessionId = sessionId,
                        jobId = jobId,
                        announceOnWrite = false,
                        originatingUserTurnId = originatingUserTurnId,
                        requestHash = requestHash,
                        argumentHash = argumentHash,
                        resultHash = resultHash,
                        producer = producer,
                    )
                    updated to HermesQueuePersistenceResult.Mutated
                }
            }
        }
    }

    internal suspend fun markLiveKitResultAnnounced(
        callId: String,
        jobId: String,
        assistantTurnId: String,
        voiceSessionId: String,
    ): HermesQueuePersistenceResult {
        return updateWithResult { conversation ->
            val recordPart = conversation.currentMessages
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>()
                .mapNotNull { part ->
                    HermesQueueRecord.fromToolPart(part)?.let { record -> part to record }
                }
                .lastOrNull { (_, record) ->
                    record.matchesIdentity(callId = callId, jobId = jobId)
                }
            val record = recordPart?.second
            val recordSessionId =
                recordPart?.first?.metadata?.stringOrNull(VOICE_SESSION_ID_KEY)
            val resultHash = record?.resultHash
            val hasGroundedAssistantTurn =
                record?.status == HermesQueueStatus.Complete &&
                    resultHash != null &&
                    recordSessionId == voiceSessionId &&
                    conversation.currentMessages.any { message ->
                        message.role == MessageRole.ASSISTANT &&
                            message.parts.filterIsInstance<UIMessagePart.Text>().any textPart@{ part ->
                                val metadata = part.metadata ?: return@textPart false
                                metadata.stringOrNull(VOICE_EVENT_ID_KEY) == assistantTurnId &&
                                    metadata.stringOrNull(VOICE_GROUNDED_JOB_ID_KEY) == jobId &&
                                    metadata.stringOrNull(VOICE_GROUNDED_RESULT_HASH_KEY) == resultHash &&
                                    metadata.stringOrNull(VOICE_SESSION_ID_KEY) == voiceSessionId
                            }
                    }
            if (!hasGroundedAssistantTurn) {
                conversation to HermesQueuePersistenceResult.Conflict
            } else {
                val updated = writer.markResultAnnounced(
                    conversation = conversation,
                    callId = callId,
                    jobId = jobId,
                )
                updated to if (updated === conversation) {
                    HermesQueuePersistenceResult.Equivalent
                } else {
                    HermesQueuePersistenceResult.Mutated
                }
            }
        }
    }

    suspend fun persistActive(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String,
        originatingUserTurnId: String? = null,
        requestHash: String? = null,
        argumentHash: String? = null,
        producer: String? = null,
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val updated = writer.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = status,
                sessionId = sessionId,
                jobId = jobId,
                originatingUserTurnId = originatingUserTurnId,
                requestHash = requestHash,
                argumentHash = argumentHash,
                producer = producer,
            )
            updated to (updated !== conversation)
        }
    }

    suspend fun persistPending(callId: String, prompt: String): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val updated = writer.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = VoiceToolRecordStatus.Pending,
                sessionId = sessionId,
                jobId = null,
            )
            updated to (updated !== conversation)
        }
    }

    suspend fun persistCanceled(
        callId: String,
        prompt: String,
        jobId: String?,
        message: String,
        announced: Boolean,
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val updated = writer.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = VoiceToolRecordStatus.Canceled(message),
                sessionId = sessionId,
                jobId = jobId,
                announceOnWrite = announced,
            )
            updated to (updated !== conversation)
        }
    }

    suspend fun persistTerminal(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String?,
        announced: Boolean?,
        originatingUserTurnId: String? = null,
        requestHash: String? = null,
        argumentHash: String? = null,
        resultHash: String? = null,
        producer: String? = null,
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val updated = writer.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = status,
                sessionId = sessionId,
                jobId = jobId,
                announceOnWrite = announced == true,
                originatingUserTurnId = originatingUserTurnId,
                requestHash = requestHash,
                argumentHash = argumentHash,
                resultHash = resultHash,
                producer = producer,
            )
            updated to (updated !== conversation)
        }
    }

    suspend fun appendVisibleResultMessageIfNeeded(callId: String, jobId: String?): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val record = conversation.hermesQueueRecords()
                .lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }
            if (record == null || !record.status.isTerminal || record.messageWritten || record.resultAnnounced) {
                conversation to false
            } else {
                val appended = transcriptPersister.appendAssistantTurn(
                    conversation = conversation,
                    text = hermesResultMessageText(record),
                    interrupted = false,
                    sessionId = sessionId,
                )
                writer.markMessageWritten(
                    conversation = appended,
                    callId = callId,
                    jobId = jobId,
                ) to true
            }
        }
    }

    private fun hermesResultMessageText(record: HermesQueueRecord): String {
        val answer = record.answer
        return if (record.status == HermesQueueStatus.Complete && answer != null) {
            "Hermes finished: ${record.prompt}\n\n$answer"
        } else {
            "Hermes could not finish: ${record.prompt}" +
                (record.error?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
        }
    }

    private suspend fun update(
        transform: (Conversation) -> Conversation,
    ) {
        updateMutex.withLock {
            conversationStore.update(transform)
        }
    }

    private suspend fun <T> updateWithResult(
        transform: (Conversation) -> Pair<Conversation, T>,
    ): T {
        return updateMutex.withLock {
            var result: T? = null
            conversationStore.update { conversation ->
                val (updatedConversation, transformResult) = transform(conversation)
                result = transformResult
                updatedConversation
            }
            requireNotNull(result)
        }
    }

    private fun HermesQueueRecord.hasLiveKitProvenance(
        prompt: String,
        originatingUserTurnId: String,
        requestHash: String,
        argumentHash: String,
        producer: String,
    ): Boolean =
        this.prompt == prompt &&
            hasLiveKitCorrelation(
                originatingUserTurnId = originatingUserTurnId,
                requestHash = requestHash,
                argumentHash = argumentHash,
                producer = producer,
            )

    private fun HermesQueueRecord.hasLiveKitCorrelation(
        originatingUserTurnId: String,
        requestHash: String,
        argumentHash: String,
        producer: String,
    ): Boolean =
        this.originatingUserTurnId == originatingUserTurnId &&
            this.requestHash == requestHash &&
            this.argumentHash == argumentHash &&
            this.producer == producer

    private fun HermesQueueRecord.hasEquivalentTerminal(
        status: VoiceToolRecordStatus,
        resultHash: String?,
    ): Boolean {
        if (this.status != status.queueStatus || this.resultHash != resultHash) return false
        return when (status) {
            is VoiceToolRecordStatus.Complete -> answer == status.answer && error == null
            is VoiceToolRecordStatus.Failed -> answer == null && error == status.message
            is VoiceToolRecordStatus.Expired -> answer == null && error == status.message
            is VoiceToolRecordStatus.Canceled -> answer == null && error == status.message
            VoiceToolRecordStatus.Pending,
            VoiceToolRecordStatus.Queued,
            VoiceToolRecordStatus.Running,
                -> false
        }
    }

    private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
