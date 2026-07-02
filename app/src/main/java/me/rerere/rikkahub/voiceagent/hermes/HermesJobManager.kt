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
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceToolStatus
import me.rerere.rikkahub.voiceagent.isTerminalHermesToolStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.summarizeVoiceToolStatus
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.voiceTextPayload
import me.rerere.rikkahub.voiceagent.voicelab.HermesJobStatus
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabHttpException
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
    private val announcementMutex = Mutex()
    private val queueStore = HermesQueueStore(
        conversationStore = conversationStore,
        persister = persister,
        persistenceSessionId = ::currentPersistenceSessionId,
    )
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
        queueStore.records()
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
        val persistedRecord = queueStore.records()
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
            val canceled = queueStore.persistCanceledIfStillActive(
                callId = callId,
                prompt = prompt,
                jobId = initialJobId,
                message = CANCELED_MESSAGE,
            )
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
            val pendingPersisted = queueStore.persistPendingIfStillActive(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                shouldPersist = { !managedJob.explicitlyCanceled },
            )
            if (!pendingPersisted) return
            recordEventSafely(
                name = "voicelab.mobile.hermes_tool.submitted",
                attributes = mapOf("callId" to managedJob.callId) +
                    voiceTextPayload(key = "gemini.tool_call.prompt", text = managedJob.prompt),
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
                queueStore.persistCanceledIfStillActive(
                    callId = managedJob.callId,
                    prompt = managedJob.prompt,
                    jobId = submitted.jobId,
                    message = CANCELED_MESSAGE,
                )
                cancelRemoteJob(submitted.jobId)
                return
            }
            val shouldPoll = persistSubmittedStatus(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = submitted.jobId,
                status = submitted.status,
                failureMessage = submitted.failure?.safeMessage,
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
                    status = submitted.status.wireName,
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

            when (poll.status) {
                HermesJobStatus.Accepted,
                HermesJobStatus.Queued -> {
                    val persisted = queueStore.persistActiveIfStillActive(
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

                HermesJobStatus.Running -> {
                    val persisted = queueStore.persistActiveIfStillActive(
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

                HermesJobStatus.Succeeded -> {
                    val answer = requireNotNull(poll.answer) { "Hermes job succeeded without an answer" }
                    val elapsedMs = managedJob.elapsedMs()
                    val persisted = queueStore.persistTerminalIfStillActive(
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
                        ) + voiceTextPayload(key = "hermes.response.answer", text = answer),
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

                HermesJobStatus.Failed -> {
                    val failureMessage = poll.failure?.safeMessage ?: DEFAULT_FAILURE_MESSAGE
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Failed(failureMessage),
                        visibleMessage = failureMessage,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }

                HermesJobStatus.Expired -> {
                    val failureMessage = poll.failure?.safeMessage ?: EXPIRED_MESSAGE
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Expired(failureMessage),
                        visibleMessage = failureMessage,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }

                HermesJobStatus.Canceled -> {
                    val failureMessage = poll.failure?.safeMessage ?: CANCELED_MESSAGE
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Canceled(failureMessage),
                        visibleMessage = failureMessage,
                        shouldPersist = { !managedJob.explicitlyCanceled },
                    )
                    return
                }
            }

            delay(pollIntervalMs)
        }
    }

    private suspend fun persistSubmittedStatus(
        callId: String,
        prompt: String,
        jobId: String,
        status: HermesJobStatus,
        failureMessage: String?,
        shouldPersist: () -> Boolean = { true },
    ): Boolean {
        return when (status) {
            HermesJobStatus.Running -> {
                val persisted = queueStore.persistActiveIfStillActive(
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

            HermesJobStatus.Succeeded -> error("Hermes submit response cannot be succeeded without an answer")

            HermesJobStatus.Failed -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Failed(failureMessage ?: DEFAULT_FAILURE_MESSAGE),
                    visibleMessage = failureMessage ?: DEFAULT_FAILURE_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            HermesJobStatus.Expired -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Expired(failureMessage ?: EXPIRED_MESSAGE),
                    visibleMessage = failureMessage ?: EXPIRED_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            HermesJobStatus.Canceled -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Canceled(failureMessage ?: CANCELED_MESSAGE),
                    visibleMessage = failureMessage ?: CANCELED_MESSAGE,
                    shouldPersist = shouldPersist,
                )
                false
            }

            HermesJobStatus.Accepted,
            HermesJobStatus.Queued -> {
                val persisted = queueStore.persistActiveIfStillActive(
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
        val persisted = queueStore.persistTerminalIfStillActive(
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
        queueStore.records()
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
            val record = queueStore.records()
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
                queueStore.markResultAnnounced(callId = callId, jobId = jobId)
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
            val record = queueStore.records()
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
                queueStore.markResultAnnounced(callId = callId, jobId = jobId)
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

    private fun currentPersistenceSessionId(): String? =
        persistenceSessionId() ?: currentBridgeAttachment()?.sessionId?.toString()

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
            if (status.isTerminalHermesToolStatus()) {
                toolCalls.remove(callId)
            } else {
                toolCalls[callId] = status
            }
            summarizeVoiceToolStatus(toolCalls = toolCalls, fallback = status)
        }
        _toolStatus.value = visibleStatus
        runCatching {
            updateToolStatus(status)
        }
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

    private val HermesJobStatus.wireName: String
        get() = when (this) {
            HermesJobStatus.Accepted -> "accepted"
            HermesJobStatus.Queued -> "queued"
            HermesJobStatus.Running -> "running"
            HermesJobStatus.Succeeded -> "succeeded"
            HermesJobStatus.Failed -> "failed"
            HermesJobStatus.Expired -> "expired"
            HermesJobStatus.Canceled -> "canceled"
        }

    private fun Throwable.isTerminalHermesPollFailure(): Boolean {
        if (this !is VoiceLabHttpException) return false
        failure?.let { return !it.retryable }
        return statusCode.isTerminalVoiceLabStatusCode()
    }

    private fun Int.isTerminalVoiceLabStatusCode(): Boolean =
        this in 400..499 && this != 408 && this != 429

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
