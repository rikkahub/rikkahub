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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceToolStatus
import me.rerere.rikkahub.voiceagent.hermesCompletionFollowUpText
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
    suspend fun sendQueuedAcknowledgement(callId: String, sessionId: Long): Boolean
    suspend fun sendCompletionFollowUp(callId: String, prompt: String, answer: String, sessionId: Long): Boolean
    suspend fun sendTerminalFollowUp(
        callId: String,
        prompt: String,
        status: HermesQueueStatus,
        reason: String,
        sessionId: Long,
    ): Boolean
    suspend fun sendStillWorkingUpdate(callId: String, prompt: String, sessionId: Long): Boolean
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
    private val stillWorkingThresholdMs: Long = DEFAULT_STILL_WORKING_THRESHOLD_MS,
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
                        requiresQueuedAcknowledgement = false,
                    ).also {
                        activeJobs[activeKey] = it
                    }
                }
                launchStillWorkingTimer(managedJob, alreadyAnnounced = record.stillWorkingAnnounced)
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
            managedJob.stillWorkingJob?.cancel()
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
                attributes = mapOf(
                    "callId" to managedJob.callId,
                    "gemini.tool_call.call_id" to managedJob.callId,
                ) +
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
                terminalAcknowledgement = {
                    acknowledgeQueuedCallIfNeeded(managedJob)
                },
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
            acknowledgeQueuedCallIfNeeded(managedJob)
            launchStillWorkingTimer(managedJob, alreadyAnnounced = false)
            pollHermesJob(managedJob = managedJob, jobId = submitted.jobId)
        } catch (error: CancellationException) {
            if (!managedJob.explicitlyCanceled) throw error
        } catch (error: Throwable) {
            val jobId = managedJob.jobId
            if (jobId == null) {
                persistFailure(
                    callId = managedJob.callId,
                    prompt = managedJob.prompt,
                    jobId = null,
                    error = error,
                    shouldPersist = { !managedJob.explicitlyCanceled },
                )
            } else {
                persistFailureAfterAcknowledgement(
                    managedJob = managedJob,
                    jobId = jobId,
                    error = error,
                )
            }
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
                    completeFailureStatusAfterAcknowledgement(
                        managedJob = managedJob,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Failed(error.message ?: error.javaClass.simpleName),
                        visibleMessage = error.message ?: error.javaClass.simpleName,
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
                            "gemini.tool_call.call_id" to managedJob.callId,
                            "hermes_job_id" to jobId,
                            "hermes_job_status" to "succeeded",
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
                    completeFailureStatusAfterAcknowledgement(
                        managedJob = managedJob,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Failed(failureMessage),
                        visibleMessage = failureMessage,
                    )
                    return
                }

                HermesJobStatus.Expired -> {
                    val failureMessage = poll.failure?.safeMessage ?: EXPIRED_MESSAGE
                    completeFailureStatusAfterAcknowledgement(
                        managedJob = managedJob,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Expired(failureMessage),
                        visibleMessage = failureMessage,
                    )
                    return
                }

                HermesJobStatus.Canceled -> {
                    val failureMessage = poll.failure?.safeMessage ?: CANCELED_MESSAGE
                    completeFailureStatusAfterAcknowledgement(
                        managedJob = managedJob,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Canceled(failureMessage),
                        visibleMessage = failureMessage,
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
        terminalAcknowledgement: suspend () -> Boolean = { false },
    ): Boolean {
        suspend fun persistTerminal(
            status: VoiceToolRecordStatus,
            visibleMessage: String,
        ): Boolean {
            val persisted = completeFailureStatus(
                callId = callId,
                prompt = prompt,
                jobId = jobId,
                status = status,
                visibleMessage = visibleMessage,
                shouldPersist = shouldPersist,
            )
            if (persisted && terminalAcknowledgement()) {
                announceTerminalResult(callId = callId, jobId = jobId)
            }
            return false
        }

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
                persistTerminal(
                    status = VoiceToolRecordStatus.Failed(failureMessage ?: DEFAULT_FAILURE_MESSAGE),
                    visibleMessage = failureMessage ?: DEFAULT_FAILURE_MESSAGE,
                )
            }

            HermesJobStatus.Expired -> {
                persistTerminal(
                    status = VoiceToolRecordStatus.Expired(failureMessage ?: EXPIRED_MESSAGE),
                    visibleMessage = failureMessage ?: EXPIRED_MESSAGE,
                )
            }

            HermesJobStatus.Canceled -> {
                persistTerminal(
                    status = VoiceToolRecordStatus.Canceled(failureMessage ?: CANCELED_MESSAGE),
                    visibleMessage = failureMessage ?: CANCELED_MESSAGE,
                )
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
            persistFailureAfterAcknowledgement(
                managedJob = managedJob,
                jobId = jobId,
                error = error,
            )
        }
    }

    private suspend fun completeFailureStatusAfterAcknowledgement(
        managedJob: ManagedHermesJob,
        jobId: String,
        status: VoiceToolRecordStatus,
        visibleMessage: String,
    ): Boolean {
        val persisted = completeFailureStatus(
            callId = managedJob.callId,
            prompt = managedJob.prompt,
            jobId = jobId,
            status = status,
            visibleMessage = visibleMessage,
            shouldPersist = { !managedJob.explicitlyCanceled },
        )
        if (persisted && acknowledgeQueuedCallIfNeeded(managedJob)) {
            announceTerminalResult(callId = managedJob.callId, jobId = jobId)
        }
        return persisted
    }

    private suspend fun persistFailureAfterAcknowledgement(
        managedJob: ManagedHermesJob,
        jobId: String,
        error: Throwable,
    ) {
        completeFailureStatusAfterAcknowledgement(
            managedJob = managedJob,
            jobId = jobId,
            status = VoiceToolRecordStatus.Failed(error.message ?: error.javaClass.simpleName),
            visibleMessage = error.message ?: error.javaClass.simpleName,
        )
    }

    private suspend fun completeFailureStatus(
        callId: String,
        prompt: String,
        jobId: String?,
        status: VoiceToolRecordStatus,
        visibleMessage: String,
        shouldPersist: () -> Boolean = { true },
        shouldAnnounceTerminalResult: Boolean = false,
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
                "gemini.tool_call.call_id" to callId,
                "hermes_job_id" to jobId,
                "hermes_job_status" to status.queueEventStatus(),
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
        if (shouldAnnounceTerminalResult) {
            announceTerminalResult(callId = callId, jobId = jobId)
        }
        return true
    }

    private suspend fun expireTimedOutJob(managedJob: ManagedHermesJob, jobId: String) {
        val expired = completeFailureStatusAfterAcknowledgement(
            managedJob = managedJob,
            jobId = jobId,
            status = VoiceToolRecordStatus.Expired(TIMEOUT_MESSAGE),
            visibleMessage = TIMEOUT_MESSAGE,
        )
        if (expired) cancelRemoteJob(jobId)
    }

    private suspend fun persistFailure(
        callId: String,
        prompt: String,
        jobId: String?,
        error: Throwable,
        shouldPersist: () -> Boolean = { true },
        shouldAnnounceTerminalResult: Boolean = jobId != null,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        completeFailureStatus(
            callId = callId,
            prompt = prompt,
            jobId = jobId,
            status = VoiceToolRecordStatus.Failed(message),
            visibleMessage = message,
            shouldPersist = shouldPersist,
            shouldAnnounceTerminalResult = shouldAnnounceTerminalResult,
        )
    }

    private suspend fun sendQueuedAcknowledgementIfAttached(
        callId: String,
        shouldSend: () -> Boolean = { true },
    ): Boolean {
        val attachment = currentBridgeAttachment() ?: return false
        if (!shouldSend()) return false
        return runCatching {
            withTimeoutOrNull(bridgeSendTimeoutMs) {
                if (!shouldSend()) return@withTimeoutOrNull false
                if (!attachment.isCurrentBridgeAttachment()) return@withTimeoutOrNull false
                attachment.bridge.sendQueuedAcknowledgement(callId = callId, sessionId = attachment.sessionId)
            } ?: false
        }.getOrDefault(false)
    }

    private suspend fun acknowledgeQueuedCallIfNeeded(managedJob: ManagedHermesJob): Boolean {
        if (managedJob.queuedAcknowledged) return true
        val acknowledged = sendQueuedAcknowledgementIfAttached(
            callId = managedJob.callId,
            shouldSend = { !managedJob.explicitlyCanceled },
        )
        if (acknowledged) {
            managedJob.queuedAcknowledged = true
        }
        return acknowledged
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
                    current.bridge.sendCompletionFollowUp(
                        callId = callId,
                        prompt = record.prompt,
                        answer = requireNotNull(record.answer),
                        sessionId = current.sessionId,
                    )
                } ?: false
            }.getOrDefault(false)
            if (sent) {
                queueStore.markResultAnnounced(callId = callId, jobId = jobId)
                recordEventSafely(
                    name = "voicelab.mobile.gemini.followup_sent",
                    attributes = mapOf(
                        "callId" to callId,
                        "jobId" to jobId,
                        "gemini.tool_call.call_id" to callId,
                        "hermes_job_id" to jobId,
                        "sent" to true,
                    ) + voiceTextPayload(
                        key = "gemini.followup_text",
                        text = hermesCompletionFollowUpText(prompt = record.prompt, answer = record.answer),
                    ),
                )
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
                    current.bridge.sendTerminalFollowUp(
                        callId = callId,
                        prompt = record.prompt,
                        status = record.status,
                        reason = record.error.orEmpty(),
                        sessionId = current.sessionId,
                    )
                } ?: false
            }.getOrDefault(false)
            if (sent) {
                queueStore.markResultAnnounced(callId = callId, jobId = jobId)
            }
        }
    }

    private fun launchStillWorkingTimer(managedJob: ManagedHermesJob, alreadyAnnounced: Boolean) {
        if (alreadyAnnounced) return
        managedJob.stillWorkingJob = scope.launch(dispatcher) {
            val remaining = (stillWorkingThresholdMs - managedJob.elapsedMs()).coerceAtLeast(0L)
            delay(remaining)
            announceStillWorkingUpdate(managedJob)
        }
    }

    private suspend fun announceStillWorkingUpdate(managedJob: ManagedHermesJob) {
        if (managedJob.explicitlyCanceled || !managedJob.queuedAcknowledged) return
        val current = currentBridgeAttachment() ?: return
        announcementMutex.withLock {
            val jobId = managedJob.jobId
            val record = queueStore.records().lastOrNull { record ->
                record.callId == managedJob.callId && when {
                    jobId != null -> record.jobId == jobId
                    else -> record.jobId == null
                }
            } ?: return@withLock
            if (record.status.isTerminal || record.stillWorkingAnnounced) return@withLock
            if (!current.isCurrentBridgeAttachment()) return@withLock
            val sent = runCatching {
                withTimeoutOrNull(bridgeSendTimeoutMs) {
                    current.bridge.sendStillWorkingUpdate(
                        callId = managedJob.callId,
                        prompt = record.prompt,
                        sessionId = current.sessionId,
                    )
                } ?: false
            }.getOrDefault(false)
            if (sent) {
                queueStore.markStillWorkingAnnounced(callId = managedJob.callId, jobId = jobId)
                safeRecordDiagnostic(
                    "hermes_still_working_announced",
                    "callId=${managedJob.callId}${jobId?.let { ", jobId=$it" }.orEmpty()}",
                )
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
        requiresQueuedAcknowledgement: Boolean = true,
    ) {
        var job: Job? = null
        var stillWorkingJob: Job? = null
        @Volatile
        var explicitlyCanceled: Boolean = false
        @Volatile
        var queuedAcknowledged: Boolean = !requiresQueuedAcknowledgement

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
        const val DEFAULT_BRIDGE_SEND_TIMEOUT_MS = 30_000L
        const val DEFAULT_STILL_WORKING_THRESHOLD_MS = 45_000L
    }
}
