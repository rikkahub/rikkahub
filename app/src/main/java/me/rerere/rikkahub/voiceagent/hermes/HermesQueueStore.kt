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
