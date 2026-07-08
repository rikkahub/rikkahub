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

    suspend fun persistActiveIfStillActive(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val latestRecord = conversation.latestHermesRecord(callId = callId, jobId = jobId)
            if (!shouldPersist() || latestRecord?.status?.isTerminal == true) {
                conversation to false
            } else {
                writer.upsertHermesTool(
                    conversation = conversation,
                    callId = callId,
                    prompt = prompt,
                    status = status,
                    sessionId = sessionId,
                    jobId = jobId,
                ) to true
            }
        }
    }

    suspend fun persistPendingIfStillActive(
        callId: String,
        prompt: String,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            if (!shouldPersist()) {
                conversation to false
            } else {
                writer.upsertHermesTool(
                    conversation = conversation,
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Pending,
                    sessionId = sessionId,
                    jobId = null,
                ) to true
            }
        }
    }

    suspend fun persistCanceledIfStillActive(
        callId: String,
        prompt: String,
        jobId: String?,
        message: String,
        resultAnnounced: Boolean? = null,
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val latestRecord = conversation.latestHermesRecord(callId = callId, jobId = jobId)
            if (latestRecord?.status?.isTerminal == true) {
                conversation to false
            } else {
                writer.upsertHermesTool(
                    conversation = conversation,
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Canceled(message),
                    sessionId = sessionId,
                    jobId = jobId,
                    announceOnWrite = resultAnnounced == true,
                ) to true
            }
        }
    }

    suspend fun persistTerminalIfStillActive(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String?,
        resultAnnounced: Boolean? = null,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val latestRecord = conversation.latestHermesRecord(callId = callId, jobId = jobId)
            if (!shouldPersist() || latestRecord?.status?.isTerminal == true) {
                conversation to false
            } else {
                writer.upsertHermesTool(
                    conversation = conversation,
                    callId = callId,
                    prompt = prompt,
                    status = status,
                    sessionId = sessionId,
                    jobId = jobId,
                    announceOnWrite = resultAnnounced == true,
                ) to true
            }
        }
    }

    suspend fun appendVisibleResultMessageIfNeeded(callId: String, jobId: String?): Boolean {
        val sessionId = persistenceSessionId()
        return updateWithResult { conversation ->
            val record = conversation.latestHermesRecord(callId = callId, jobId = jobId)
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

    private fun Conversation.latestHermesRecord(callId: String, jobId: String?): HermesQueueRecord? =
        hermesQueueRecords().lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }
}
