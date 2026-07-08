package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister

class HermesQueueStore(
    private val conversationStore: VoiceConversationStore,
    private val writer: HermesToolRecordWriter,
    private val transcriptPersister: VoiceTranscriptPersister,
    private val persistenceSessionId: () -> String? = { null },
) {
    private val updateMutex = Mutex()

    fun records(): List<HermesQueueRecord> =
        conversationStore.conversation.value.hermesQueueRecords()

    fun latestRecord(callId: String, jobId: String?): HermesQueueRecord? =
        records().lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }

    fun activeRecords(): List<HermesQueueRecord> =
        records().latestByHermesDurableIdentity().filter { !it.status.isTerminal }

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

    suspend fun persistActive(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String,
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
}
