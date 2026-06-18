package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceToolStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.voiceTextPayload
import java.time.Instant

interface HermesSessionBridge {
    fun sendQueuedAcknowledgement(callId: String, sessionId: Long): Boolean
    fun sendCompletionFollowUp(callId: String, prompt: String, answer: String, sessionId: Long): Boolean
    fun sendTerminalFollowUp(
        callId: String,
        prompt: String,
        status: HermesQueueStatus,
        reason: String,
        sessionId: Long,
    ): Boolean
}

data class HermesJobCompletion(
    val callId: String,
    val jobId: String,
    val answer: String,
    val elapsedMs: Long,
    val serverElapsedMs: Long?,
)

data class HermesJobFailure(
    val callId: String,
    val jobId: String?,
    val message: String,
    val elapsedMs: Long,
)

data class HermesPollFailure(
    val callId: String,
    val jobId: String,
    val attempt: Int,
    val message: String,
)

class HermesJobManager(
    private val toolApi: VoiceToolApi,
    private val conversationStore: VoiceConversationStore,
    private val persister: VoiceConversationPersister,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val pollRetryDelayMs: Long = DEFAULT_POLL_RETRY_DELAY_MS,
    private val maxElapsedMs: Long = DEFAULT_MAX_ELAPSED_MS,
    private val remoteCancelTimeoutMs: Long = DEFAULT_REMOTE_CANCEL_TIMEOUT_MS,
    private val bridgeSendTimeoutMs: Long = DEFAULT_BRIDGE_SEND_TIMEOUT_MS,
    private val updateToolStatus: (VoiceToolStatus) -> Unit = {},
    private val recordDiagnostic: (String, String) -> Unit = { _, _ -> },
    private val writeQueueEvent: (String) -> Unit = {},
    private val writeHermesAnswer: (String) -> Unit = {},
    private val persistenceSessionId: () -> String? = { null },
    private val onJobCompleted: (HermesJobCompletion) -> Unit = {},
    private val onJobFailed: (HermesJobFailure) -> Unit = {},
    private val onPollFailed: (HermesPollFailure) -> Unit = {},
    private val observability: VoiceObservability = NoOpVoiceObservability,
    private val traceContext: VoiceTraceContext = newVoiceTraceContext(),
) {
    private val lock = Any()
    private val conversationUpdateMutex = Mutex()
    private val announcementMutex = Mutex()
    private val activeJobs = mutableMapOf<String, ManagedHermesJob>()
    private val toolCalls = mutableMapOf<String, VoiceToolStatus>()
    private val _toolStatus = MutableStateFlow<VoiceToolStatus>(VoiceToolStatus.Idle)
    val toolStatus: StateFlow<VoiceToolStatus> = _toolStatus
    private var bridgeAttachment: BridgeAttachment? = null

    fun submit(callId: String, prompt: String): Boolean {
        return submit(callId = callId, prompt = prompt, activeKey = callActiveKey(callId))
    }

    fun submit(callId: String, prompt: String, activeKey: String): Boolean {
        val managedJob = synchronized(lock) {
            if (activeJobs.containsKey(activeKey)) return false
            ManagedHermesJob(
                activeKey = activeKey,
                callId = callId,
                prompt = prompt,
            ).also {
                activeJobs[activeKey] = it
            }
        }
        launchManagedJob(managedJob) {
            submitAndPoll(managedJob)
        }
        return true
    }

    fun resumeActiveJobs() {
        conversationStore.conversation.value.hermesQueueRecords()
            .latestByHermesDurableIdentity()
            .filter { !it.status.isTerminal }
            .forEach { record ->
                val activeKey = record.activeKey()
                if (synchronized(lock) { hasActiveJob(record = record, activeKey = activeKey) }) return@forEach
                val jobId = record.jobId
                if (jobId == null) {
                    scope.launch(dispatcher) {
                        completeFailureStatus(
                            callId = record.callId,
                            prompt = record.prompt,
                            jobId = null,
                            status = VoiceToolRecordStatus.Expired(MISSING_JOB_ID_MESSAGE),
                            visibleMessage = MISSING_JOB_ID_MESSAGE,
                        )
                    }
                    return@forEach
                }
                val startedAtMs = record.createdAt.toEpochMillisOrNull()
                    ?: record.updatedAt.toEpochMillisOrNull()
                    ?: run {
                        scope.launch(dispatcher) {
                            completeFailureStatus(
                                callId = record.callId,
                                prompt = record.prompt,
                                jobId = jobId,
                                status = VoiceToolRecordStatus.Expired(INVALID_TIMESTAMP_MESSAGE),
                                visibleMessage = INVALID_TIMESTAMP_MESSAGE,
                            )
                        }
                        return@forEach
                }
                val managedJob = synchronized(lock) {
                    if (hasActiveJob(record = record, activeKey = activeKey)) return@forEach
                    ManagedHermesJob(
                        activeKey = activeKey,
                        callId = record.callId,
                        prompt = record.prompt,
                        jobId = jobId,
                        startedAtMs = startedAtMs,
                    ).also {
                        activeJobs[activeKey] = it
                    }
                }
                launchManagedJob(managedJob) {
                    pollHermesJobSafely(managedJob, jobId)
                }
        }
    }

    private fun hasActiveJob(record: HermesQueueRecord, activeKey: String): Boolean {
        if (activeJobs.containsKey(activeKey)) return true
        return activeJobs.values.any { managedJob ->
            when {
                record.jobId != null -> managedJob.jobId == record.jobId
                else -> managedJob.callId == record.callId && managedJob.jobId == null
            }
        }
    }

    suspend fun awaitJobs() {
        while (true) {
            val jobs = synchronized(lock) {
                if (activeJobs.isEmpty()) return
                activeJobs.values.mapNotNull { it.job }
            }
            jobs.joinAll()
        }
    }

    private fun expireSupersededActiveRecord(record: HermesQueueRecord) {
        scope.launch(dispatcher) {
            val expired = completeFailureStatus(
                callId = record.callId,
                prompt = record.prompt,
                jobId = record.jobId,
                status = VoiceToolRecordStatus.Expired(SUPERSEDED_JOB_MESSAGE),
                visibleMessage = SUPERSEDED_JOB_MESSAGE,
            )
            if (expired) {
                record.jobId?.let { cancelRemoteJob(it) }
            }
        }
    }

    fun attachBridge(bridge: HermesSessionBridge, sessionId: Long) {
        synchronized(lock) {
            bridgeAttachment?.active = false
            bridgeAttachment = BridgeAttachment(bridge = bridge, sessionId = sessionId)
        }
        scope.launch(dispatcher) {
            announceUnannouncedTerminalResults(bridge = bridge, sessionId = sessionId)
        }
    }

    fun attachBridge(bridge: HermesSessionBridge) {
        attachBridge(bridge = bridge, sessionId = 0L)
    }

    fun detachBridge(bridge: HermesSessionBridge) {
        synchronized(lock) {
            val attachment = bridgeAttachment
            if (attachment?.bridge === bridge) {
                attachment.active = false
                bridgeAttachment = null
            }
        }
    }

    fun cancel(callId: String) {
        cancel(callId = callId, activeKey = null)
    }

    fun cancel(callId: String, activeKey: String?) {
        val managedJob = synchronized(lock) {
            val job = activeKey?.let { activeJobs[it] }
                ?: activeJobs.values.lastOrNull { it.callId == callId }
            job?.also { it.explicitlyCanceled = true }
        }
        val persistedRecord = conversationStore.conversation.value.hermesQueueRecords()
            .lastOrNull { record ->
                record.callId == callId && when {
                    managedJob?.jobId != null -> record.jobId == managedJob.jobId
                    activeKey != null -> record.jobId == null
                    else -> true
                }
            }
        if (managedJob == null && persistedRecord?.status?.isTerminal != false) return

        val prompt = managedJob?.prompt ?: persistedRecord?.prompt.orEmpty()
        val initialJobId = synchronized(lock) {
            managedJob?.jobId ?: if (managedJob == null) persistedRecord?.jobId else null
        }
        scope.launch(dispatcher) {
            val canceled = persistCanceledIfStillActive(callId = callId, prompt = prompt, jobId = initialJobId)
            if (canceled) {
                updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = CANCELED_MESSAGE))
                val latestManagedJobId = synchronized(lock) {
                    managedJob?.jobId ?: activeJobs.values.lastOrNull { it.callId == callId }?.jobId
                }
                val latestJobId = latestManagedJobId
                    ?: if (managedJob == null) persistedRecord?.jobId ?: initialJobId else initialJobId
                latestJobId?.let { cancelRemoteJob(it) }
                if (latestJobId != null) {
                    managedJob?.job?.cancel()
                }
            }
        }
    }

    private fun launchManagedJob(
        managedJob: ManagedHermesJob,
        block: suspend () -> Unit,
    ) {
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            block()
        }
        managedJob.job = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJobs[managedJob.activeKey] === managedJob) {
                    activeJobs.remove(managedJob.activeKey)
                }
            }
        }
        job.start()
    }

    private suspend fun submitAndPoll(managedJob: ManagedHermesJob) {
        try {
            val pendingPersisted = persistPendingIfStillActive(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                shouldPersist = { !managedJob.explicitlyCanceled },
            )
            if (!pendingPersisted) return
            recordEventSafely(
                name = "voicelab.mobile.hermes_tool.submitted",
                attributes = mapOf("callId" to managedJob.callId) +
                    voiceTextPayload(key = "prompt", text = managedJob.prompt),
            )
            if (managedJob.explicitlyCanceled) return
            val submitted = withTimeoutOrNull(managedJob.remainingMs(maxElapsedMs)) {
                toolApi.submitHermesJob(callId = managedJob.callId, prompt = managedJob.prompt)
            } ?: run {
                completeFailureStatus(
                    callId = managedJob.callId,
                    prompt = managedJob.prompt,
                    jobId = null,
                    status = VoiceToolRecordStatus.Expired(TIMEOUT_MESSAGE),
                    visibleMessage = TIMEOUT_MESSAGE,
                    shouldPersist = { !managedJob.explicitlyCanceled },
                )
                return
            }
            synchronized(lock) {
                managedJob.jobId = submitted.jobId
            }
            if (managedJob.explicitlyCanceled) {
                persistCanceledIfStillActive(
                    callId = managedJob.callId,
                    prompt = managedJob.prompt,
                    jobId = submitted.jobId,
                )
                cancelRemoteJob(submitted.jobId)
                return
            }
            val shouldPoll = persistSubmittedStatus(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = submitted.jobId,
                status = submitted.status,
                error = null,
                shouldPersist = { !managedJob.explicitlyCanceled },
            )
            if (!shouldPoll) return
            if (managedJob.explicitlyCanceled) {
                cancelRemoteJob(submitted.jobId)
                return
            }
            safeRecordDiagnostic(
                "hermes_job_created",
                "callId=${managedJob.callId}, jobId=${submitted.jobId}, status=${submitted.status}",
            )
            safeWriteQueueEvent(
                buildQueueEvent(
                    type = "job_created",
                    callId = managedJob.callId,
                    jobId = submitted.jobId,
                    status = submitted.status,
                )
            )
            sendQueuedAcknowledgementIfAttached(
                callId = managedJob.callId,
                shouldSend = { !managedJob.explicitlyCanceled },
            )
            pollHermesJob(managedJob = managedJob, jobId = submitted.jobId)
        } catch (error: CancellationException) {
            if (!managedJob.explicitlyCanceled) throw error
        } catch (error: Throwable) {
            persistFailure(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = managedJob.jobId,
                error = error,
                shouldPersist = { !managedJob.explicitlyCanceled },
            )
        }
    }

    private suspend fun pollHermesJob(managedJob: ManagedHermesJob, jobId: String) {
        var pollFailures = 0
        while (true) {
            if (managedJob.hasTimedOut(maxElapsedMs)) {
                expireTimedOutJob(managedJob = managedJob, jobId = jobId)
                return
            }

            val poll = try {
                withTimeoutOrNull(managedJob.remainingMs(maxElapsedMs)) {
                    toolApi.getHermesJob(jobId = jobId)
                } ?: run {
                    expireTimedOutJob(managedJob = managedJob, jobId = jobId)
                    return
                }
            } catch (error: CancellationException) {
                if (!managedJob.explicitlyCanceled) throw error
                return
            } catch (error: Throwable) {
                if (error.isTerminalHermesPollFailure()) {
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Failed(error.message ?: error.javaClass.simpleName),
                        visibleMessage = error.message ?: error.javaClass.simpleName,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }
                pollFailures += 1
                safeOnPollFailed(
                    HermesPollFailure(
                        callId = managedJob.callId,
                        jobId = jobId,
                        attempt = pollFailures,
                        message = error.message ?: error.javaClass.simpleName,
                    )
                )
                if (managedJob.hasTimedOut(maxElapsedMs)) {
                    expireTimedOutJob(managedJob = managedJob, jobId = jobId)
                    return
                }
                delay(nextPollRetryDelayMs(pollFailures))
                continue
            }
            pollFailures = 0

            when (poll.status.lowercase()) {
                "queued" -> {
                    val persisted = persistActiveIfStillActive(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        status = VoiceToolRecordStatus.Queued,
                        jobId = jobId,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    if (!persisted) return
                    updateToolStatus(
                        managedJob.callId,
                        VoiceToolStatus.QueuedHermes(callId = managedJob.callId, jobId = jobId),
                    )
                }

                "running" -> {
                    val persisted = persistActiveIfStillActive(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        status = VoiceToolRecordStatus.Running,
                        jobId = jobId,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    if (!persisted) return
                    updateToolStatus(
                        managedJob.callId,
                        VoiceToolStatus.CallingHermes(
                            callId = managedJob.callId,
                            elapsedMs = managedJob.elapsedMs(),
                        ),
                    )
                }

                "succeeded" -> {
                    val answer = requireNotNull(poll.answer) { "Hermes job succeeded without an answer" }
                    val elapsedMs = managedJob.elapsedMs()
                    val persisted = persistTerminalIfStillActive(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        status = VoiceToolRecordStatus.Complete(answer),
                        jobId = jobId,
                        resultAnnounced = false,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    if (!persisted) return
                    safeOnJobCompleted(
                        HermesJobCompletion(
                            callId = managedJob.callId,
                            jobId = jobId,
                            answer = answer,
                            elapsedMs = elapsedMs,
                            serverElapsedMs = poll.elapsedMs,
                        )
                    )
                    recordEventSafely(
                        name = "voicelab.mobile.hermes_tool.completed",
                        attributes = mapOf(
                            "callId" to managedJob.callId,
                            "jobId" to jobId,
                            "elapsedMs" to elapsedMs,
                            "serverElapsedMs" to poll.elapsedMs,
                        ) + voiceTextPayload(key = "answer", text = answer),
                    )
                    safeRecordDiagnostic(
                        "hermes_job_completed",
                        "callId=${managedJob.callId}, jobId=$jobId, elapsedMs=$elapsedMs" +
                            "${poll.elapsedMs?.let { ", serverElapsedMs=$it" }.orEmpty()}, answerChars=${answer.length}",
                    )
                    safeWriteQueueEvent(
                        buildQueueEvent(
                            type = "job_completed",
                            callId = managedJob.callId,
                            jobId = jobId,
                            status = "succeeded",
                            elapsedMs = elapsedMs,
                            serverElapsedMs = poll.elapsedMs,
                            answerChars = answer.length,
                        )
                    )
                    safeWriteHermesAnswer(answer)
                    updateToolStatus(
                        managedJob.callId,
                        VoiceToolStatus.HermesAnswered(
                            callId = managedJob.callId,
                            elapsedMs = managedJob.elapsedMs(),
                        ),
                    )
                    announceCompletedResult(
                        callId = managedJob.callId,
                        jobId = jobId,
                    )
                    return
                }

                "failed" -> {
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Failed(poll.error ?: DEFAULT_FAILURE_MESSAGE),
                        visibleMessage = poll.error ?: DEFAULT_FAILURE_MESSAGE,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }

                "expired", "timeout" -> {
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Expired(poll.error ?: EXPIRED_MESSAGE),
                        visibleMessage = poll.error ?: EXPIRED_MESSAGE,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }

                "canceled", "cancelled" -> {
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Canceled(poll.error ?: CANCELED_MESSAGE),
                        visibleMessage = poll.error ?: CANCELED_MESSAGE,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }

                else -> throw IllegalStateException("Unknown Hermes job status: ${poll.status}")
            }

            delay(pollIntervalMs)
        }
    }

    private suspend fun persistSubmittedStatus(
        callId: String,
        prompt: String,
        jobId: String,
        status: String,
        error: String?,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        return when (status.lowercase()) {
            "running" -> {
                val persisted = persistActiveIfStillActive(
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Running,
                    jobId = jobId,
                    shouldPersist = shouldPersist,
                )
                if (!persisted) return false
                updateToolStatus(callId, VoiceToolStatus.CallingHermes(callId = callId))
                true
            }

            "failed" -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Failed(error ?: DEFAULT_FAILURE_MESSAGE),
                    visibleMessage = error ?: DEFAULT_FAILURE_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            "expired" -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Expired(error ?: EXPIRED_MESSAGE),
                    visibleMessage = error ?: EXPIRED_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            "timeout" -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Expired(error ?: TIMEOUT_MESSAGE),
                    visibleMessage = error ?: TIMEOUT_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            "canceled", "cancelled" -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Canceled(error ?: CANCELED_MESSAGE),
                    visibleMessage = error ?: CANCELED_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            else -> {
                val persisted = persistActiveIfStillActive(
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Queued,
                    jobId = jobId,
                    shouldPersist = shouldPersist,
                )
                if (!persisted) return false
                updateToolStatus(callId, VoiceToolStatus.QueuedHermes(callId = callId, jobId = jobId))
                true
            }
        }
    }

    private suspend fun pollHermesJobSafely(managedJob: ManagedHermesJob, jobId: String) {
        try {
            pollHermesJob(managedJob = managedJob, jobId = jobId)
        } catch (error: CancellationException) {
            if (!managedJob.explicitlyCanceled) throw error
        } catch (error: Throwable) {
            persistFailure(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = jobId,
                error = error,
                shouldPersist = { !managedJob.explicitlyCanceled },
            )
        }
    }

    private suspend fun completeFailureStatus(
        callId: String,
        prompt: String,
        jobId: String?,
        status: VoiceToolRecordStatus,
        visibleMessage: String,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val persisted = persistTerminalIfStillActive(
            callId = callId,
            prompt = prompt,
            status = status,
            jobId = jobId,
            shouldPersist = shouldPersist,
        )
        if (!persisted) return false
        recordEventSafely(
            name = "voicelab.mobile.hermes_tool.failed",
            attributes = mapOf(
                "callId" to callId,
                "jobId" to jobId,
                "status" to status.queueEventStatus(),
                "message" to visibleMessage,
            ),
        )
        safeRecordDiagnostic(
            "hermes_job_failed",
            "callId=$callId${jobId?.let { ", jobId=$it" }.orEmpty()}, message=$visibleMessage",
        )
        safeOnJobFailed(
            HermesJobFailure(
                callId = callId,
                jobId = jobId,
                message = visibleMessage,
                elapsedMs = 0L,
            )
        )
        safeWriteQueueEvent(
            buildQueueEvent(
                type = "job_failed",
                callId = callId,
                jobId = jobId ?: "none",
                status = status.queueEventStatus(),
            )
        )
        updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = visibleMessage))
        return true
    }

    private suspend fun expireTimedOutJob(managedJob: ManagedHermesJob, jobId: String) {
        val expired = completeFailureStatus(
            callId = managedJob.callId,
            prompt = managedJob.prompt,
            jobId = jobId,
            status = VoiceToolRecordStatus.Expired(TIMEOUT_MESSAGE),
            visibleMessage = TIMEOUT_MESSAGE,
            shouldPersist = { !managedJob.explicitlyCanceled },
        )
        if (expired) cancelRemoteJob(jobId)
    }

    private suspend fun persistFailure(
        callId: String,
        prompt: String,
        jobId: String?,
        error: Throwable,
        shouldPersist: () -> Boolean = { true },
    ) {
        val message = error.message ?: error.javaClass.simpleName
        completeFailureStatus(
            callId = callId,
            prompt = prompt,
            jobId = jobId,
            status = VoiceToolRecordStatus.Failed(message),
            visibleMessage = message,
            shouldPersist = shouldPersist,
        )
    }

    private suspend fun sendQueuedAcknowledgementIfAttached(
        callId: String,
        shouldSend: () -> Boolean = { true },
    ) {
        val attachment = currentBridgeAttachment() ?: return
        if (!shouldSend()) return
        runCatching {
            withTimeoutOrNull(bridgeSendTimeoutMs) {
                runInterruptible {
                    if (!shouldSend()) return@runInterruptible false
                    if (!attachment.isCurrentBridgeAttachment()) return@runInterruptible false
                    attachment.bridge.sendQueuedAcknowledgement(callId = callId, sessionId = attachment.sessionId)
                }
            }
        }
    }

    private suspend fun announceUnannouncedTerminalResults(bridge: HermesSessionBridge, sessionId: Long) {
        conversationStore.conversation.value.hermesQueueRecords()
            .latestByHermesDurableIdentity()
            .filter { it.status.isTerminal && !it.resultAnnounced }
            .forEach { record ->
                val current = currentBridgeAttachment() ?: return@forEach
                if (current.bridge !== bridge || current.sessionId != sessionId) return@forEach
                if (record.status == HermesQueueStatus.Complete && record.answer != null) {
                    announceCompletedResult(
                        callId = record.callId,
                        jobId = record.jobId,
                        attachment = current,
                    )
                } else if (record.status != HermesQueueStatus.Complete) {
                    announceTerminalResult(
                        callId = record.callId,
                        jobId = record.jobId,
                        attachment = current,
                    )
                }
            }
    }

    private suspend fun announceCompletedResult(
        callId: String,
        jobId: String?,
        attachment: BridgeAttachment? = currentBridgeAttachment(),
    ) {
        val current = attachment ?: return
        announcementMutex.withLock {
            val record = conversationStore.conversation.value.hermesQueueRecords()
                .lastOrNull { record ->
                    record.callId == callId && when {
                        jobId != null -> record.jobId == jobId
                        else -> record.jobId == null
                    }
                }
            if (record?.status != HermesQueueStatus.Complete || record.resultAnnounced || record.answer == null) return@withLock
            if (!current.isCurrentBridgeAttachment()) return@withLock

            val sent = runCatching {
                withTimeoutOrNull(bridgeSendTimeoutMs) {
                    runInterruptible {
                        current.bridge.sendCompletionFollowUp(
                            callId = callId,
                            prompt = record.prompt,
                            answer = requireNotNull(record.answer),
                            sessionId = current.sessionId,
                        )
                    }
                } ?: false
            }.getOrDefault(false)
            if (sent) {
                updateConversation { conversation ->
                    persister.markHermesToolResultAnnounced(
                        conversation = conversation,
                        callId = callId,
                        jobId = jobId,
                        matchMissingJobId = jobId == null,
                    )
                }
            }
        }
    }

    private suspend fun announceTerminalResult(
        callId: String,
        jobId: String?,
        attachment: BridgeAttachment? = currentBridgeAttachment(),
    ) {
        val current = attachment ?: return
        announcementMutex.withLock {
            val record = conversationStore.conversation.value.hermesQueueRecords()
                .lastOrNull { record ->
                    record.callId == callId && when {
                        jobId != null -> record.jobId == jobId
                        else -> record.jobId == null
                    }
                }
            if (
                record == null ||
                record.status == HermesQueueStatus.Complete ||
                !record.status.isTerminal ||
                record.resultAnnounced
            ) {
                return@withLock
            }
            if (!current.isCurrentBridgeAttachment()) return@withLock

            val sent = runCatching {
                withTimeoutOrNull(bridgeSendTimeoutMs) {
                    runInterruptible {
                        current.bridge.sendTerminalFollowUp(
                            callId = callId,
                            prompt = record.prompt,
                            status = record.status,
                            reason = record.error.orEmpty(),
                            sessionId = current.sessionId,
                        )
                    }
                } ?: false
            }.getOrDefault(false)
            if (sent) {
                updateConversation { conversation ->
                    persister.markHermesToolResultAnnounced(
                        conversation = conversation,
                        callId = callId,
                        jobId = jobId,
                        matchMissingJobId = jobId == null,
                    )
                }
            }
        }
    }

    private fun launchCompletedResultAnnouncement(
        callId: String,
        jobId: String?,
    ) {
        scope.launch(dispatcher) {
            announceCompletedResult(
                callId = callId,
                jobId = jobId,
            )
        }
    }

    private fun safeRecordDiagnostic(event: String, detail: String) {
        runCatching {
            recordDiagnostic(event, detail)
        }
    }

    private fun safeWriteQueueEvent(event: String) {
        runCatching {
            writeQueueEvent(event)
        }
    }

    private fun safeWriteHermesAnswer(answer: String) {
        runCatching {
            writeHermesAnswer(answer)
        }
    }

    private fun safeOnJobCompleted(completion: HermesJobCompletion) {
        runCatching {
            onJobCompleted(completion)
        }
    }

    private fun safeOnJobFailed(failure: HermesJobFailure) {
        runCatching {
            onJobFailed(failure)
        }
    }

    private fun safeOnPollFailed(failure: HermesPollFailure) {
        runCatching {
            onPollFailed(failure)
        }
    }

    private fun recordEventSafely(name: String, attributes: Map<String, Any?> = emptyMap()) {
        runCatching {
            observability.recordEvent(name = name, trace = traceContext, attributes = attributes)
        }
    }

    private suspend fun persistActiveIfStillActive(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val sessionId = persistenceSessionId() ?: currentBridgeAttachment()?.sessionId?.toString()
        return updateConversationWithResult { conversation ->
            val latestRecord = conversation.hermesQueueRecords().lastOrNull { record ->
                record.callId == callId && record.jobId == jobId
            }
            if (!shouldPersist() || latestRecord?.status?.isTerminal == true) {
                conversation to false
            } else {
                persister.upsertHermesTool(
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

    private suspend fun persistPendingIfStillActive(
        callId: String,
        prompt: String,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val sessionId = persistenceSessionId() ?: currentBridgeAttachment()?.sessionId?.toString()
        return updateConversationWithResult { conversation ->
            if (!shouldPersist()) {
                conversation to false
            } else {
                persister.upsertHermesTool(
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

    private suspend fun persistCanceledIfStillActive(
        callId: String,
        prompt: String,
        jobId: String?,
    ): Boolean {
        val sessionId = persistenceSessionId() ?: currentBridgeAttachment()?.sessionId?.toString()
        return updateConversationWithResult { conversation ->
            val latestRecord = conversation.hermesQueueRecords().lastOrNull { record ->
                record.callId == callId && when {
                    jobId != null -> record.jobId == jobId
                    else -> record.jobId == null
                }
            }
            if (latestRecord?.status?.isTerminal == true) {
                conversation to false
            } else {
                persister.upsertHermesTool(
                    conversation = conversation,
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Canceled(CANCELED_MESSAGE),
                    sessionId = sessionId,
                    jobId = jobId,
                ) to true
            }
        }
    }

    private suspend fun persistTerminalIfStillActive(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String?,
        resultAnnounced: Boolean? = null,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        val sessionId = persistenceSessionId() ?: currentBridgeAttachment()?.sessionId?.toString()
        return updateConversationWithResult { conversation ->
            val latestRecord = conversation.hermesQueueRecords().lastOrNull { record ->
                record.callId == callId && when {
                    jobId != null -> record.jobId == jobId
                    else -> record.jobId == null
                }
            }
            if (!shouldPersist() || latestRecord?.status?.isTerminal == true) {
                conversation to false
            } else {
                persister.upsertHermesTool(
                    conversation = conversation,
                    callId = callId,
                    prompt = prompt,
                    status = status,
                    sessionId = sessionId,
                    jobId = jobId,
                    resultAnnounced = resultAnnounced,
                ) to true
            }
        }
    }

    private suspend fun updateConversation(
        transform: (Conversation) -> Conversation,
    ) {
        conversationUpdateMutex.withLock {
            conversationStore.update(transform)
        }
    }

    private suspend fun <T> updateConversationWithResult(
        transform: (Conversation) -> Pair<Conversation, T>,
    ): T {
        return conversationUpdateMutex.withLock {
            var result: T? = null
            conversationStore.update { conversation ->
                val (updatedConversation, transformResult) = transform(conversation)
                result = transformResult
                updatedConversation
            }
            requireNotNull(result)
        }
    }

    private suspend fun cancelRemoteJob(jobId: String) {
        try {
            withTimeoutOrNull(remoteCancelTimeoutMs) {
                toolApi.cancelHermesJob(jobId = jobId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Best-effort remote cleanup must not keep local queue state active forever.
        }
    }

    private fun updateToolStatus(callId: String, status: VoiceToolStatus) {
        val visibleStatus = synchronized(lock) {
            if (status.isTerminalHermesStatus()) {
                toolCalls.remove(callId)
            } else {
                toolCalls[callId] = status
            }
            summarizeToolStatus(toolCalls = toolCalls, fallback = status)
        }
        _toolStatus.value = visibleStatus
        runCatching {
            updateToolStatus(status)
        }
    }

    private fun VoiceToolStatus.isTerminalHermesStatus(): Boolean = when (this) {
        is VoiceToolStatus.HermesAnswered,
        is VoiceToolStatus.HermesFailed,
            -> true
        VoiceToolStatus.Idle,
        is VoiceToolStatus.QueuedHermes,
        is VoiceToolStatus.CallingHermes,
            -> false
    }

    private fun VoiceToolRecordStatus.queueEventStatus(): String = when (this) {
        VoiceToolRecordStatus.Pending -> HermesQueueStatus.Pending.wireName
        VoiceToolRecordStatus.Queued -> HermesQueueStatus.Queued.wireName
        VoiceToolRecordStatus.Running -> HermesQueueStatus.Running.wireName
        is VoiceToolRecordStatus.Complete -> HermesQueueStatus.Complete.wireName
        is VoiceToolRecordStatus.Failed -> HermesQueueStatus.Failed.wireName
        is VoiceToolRecordStatus.Expired -> HermesQueueStatus.Expired.wireName
        is VoiceToolRecordStatus.Canceled -> HermesQueueStatus.Canceled.wireName
    }

    private fun summarizeToolStatus(
        toolCalls: Map<String, VoiceToolStatus>,
        fallback: VoiceToolStatus,
    ): VoiceToolStatus {
        return when (fallback) {
            is VoiceToolStatus.CallingHermes -> fallback
            is VoiceToolStatus.QueuedHermes -> fallback
            else -> toolCalls.values.filterIsInstance<VoiceToolStatus.CallingHermes>().firstOrNull()
                ?: toolCalls.values.filterIsInstance<VoiceToolStatus.QueuedHermes>().firstOrNull()
                ?: toolCalls.values.filterIsInstance<VoiceToolStatus.HermesFailed>().firstOrNull()
                ?: fallback
        }
    }

    private fun currentBridgeAttachment(): BridgeAttachment? = synchronized(lock) {
        bridgeAttachment
    }

    private fun BridgeAttachment.isCurrentBridgeAttachment(): Boolean = synchronized(lock) {
        active && bridgeAttachment?.bridge === bridge && bridgeAttachment?.sessionId == sessionId
    }

    private fun nextPollRetryDelayMs(failures: Int): Long {
        val multiplier = when {
            failures <= 1 -> 1L
            failures >= 7 -> 64L
            else -> 1L shl (failures - 1)
        }
        return (pollRetryDelayMs * multiplier)
            .coerceAtMost(pollIntervalMs)
            .coerceAtLeast(1L)
    }

    private fun buildQueueEvent(
        type: String,
        callId: String,
        jobId: String,
        status: String? = null,
        elapsedMs: Long? = null,
        serverElapsedMs: Long? = null,
        answerChars: Int? = null,
        sent: Boolean? = null,
    ): String {
        return buildJsonObject {
            put("type", type)
            put("callId", callId)
            put("jobId", jobId)
            status?.let { put("status", it) }
            elapsedMs?.let { put("elapsedMs", it) }
            serverElapsedMs?.let { put("serverElapsedMs", it) }
            answerChars?.let { put("answerChars", it) }
            sent?.let { put("sent", it) }
        }.toString()
    }

    private fun Throwable.isTerminalHermesPollFailure(): Boolean {
        val statusCode = Regex("Voice Lab request failed (\\d{3})")
            .find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return false
        return statusCode in 400..499 && statusCode != 408 && statusCode != 429
    }

    private fun String?.toEpochMillisOrNull(): Long? {
        return this
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }

    private fun HermesQueueRecord.activeKey(): String {
        return jobId?.let { "job:$it" } ?: callActiveKey(callId)
    }

    private fun callActiveKey(callId: String): String = "call:$callId"

    private class ManagedHermesJob(
        val activeKey: String,
        val callId: String,
        val prompt: String,
        var jobId: String? = null,
        private val startedAtMs: Long = System.currentTimeMillis(),
    ) {
        var job: Job? = null
        @Volatile
        var explicitlyCanceled: Boolean = false

        fun elapsedMs(): Long = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        fun hasTimedOut(maxElapsedMs: Long): Boolean = elapsedMs() >= maxElapsedMs
        fun remainingMs(maxElapsedMs: Long): Long = (maxElapsedMs - elapsedMs()).coerceAtLeast(1L)
    }

    private class BridgeAttachment(
        val bridge: HermesSessionBridge,
        val sessionId: Long,
    ) {
        @Volatile
        var active: Boolean = true
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_POLL_RETRY_DELAY_MS = 1_000L
        const val DEFAULT_MAX_ELAPSED_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_FAILURE_MESSAGE = "Hermes job was no longer available."
        const val EXPIRED_MESSAGE = "Hermes job was no longer available."
        const val TIMEOUT_MESSAGE = "Hermes job polling timed out."
        const val CANCELED_MESSAGE = "Hermes job canceled."
        const val MISSING_JOB_ID_MESSAGE = "Hermes job was missing a job id."
        const val INVALID_TIMESTAMP_MESSAGE = "Hermes job had invalid timing metadata."
        const val SUPERSEDED_JOB_MESSAGE = "Hermes job was superseded by a newer job for the same call id."
        const val DEFAULT_REMOTE_CANCEL_TIMEOUT_MS = 5_000L
        const val DEFAULT_BRIDGE_SEND_TIMEOUT_MS = 2_000L
    }
}
