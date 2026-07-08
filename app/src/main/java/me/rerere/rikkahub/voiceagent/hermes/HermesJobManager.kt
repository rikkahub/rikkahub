package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceToolStatus
import me.rerere.rikkahub.voiceagent.hermesCompletionFollowUpText
import me.rerere.rikkahub.voiceagent.isTerminalHermesToolStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import me.rerere.rikkahub.voiceagent.summarizeVoiceToolStatus
import me.rerere.rikkahub.voiceagent.telemetry.HermesTelemetryLogSanitizer
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
    suspend fun sendCancelResponse(callId: String, outcome: CancelHermesOutcome, sessionId: Long): Boolean
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

data class PendingHermesRequest(
    val callId: String,
    val jobId: String?,
    val prompt: String,
)

sealed interface CancelHermesOutcome {
    data object NothingPending : CancelHermesOutcome
    data class Canceled(val request: PendingHermesRequest) : CancelHermesOutcome
    data class NoMatch(val pending: List<PendingHermesRequest>) : CancelHermesOutcome
    data class Ambiguous(val matches: List<PendingHermesRequest>) : CancelHermesOutcome
}

class HermesJobManager(
    private val toolApi: VoiceToolApi,
    private val conversationStore: VoiceConversationStore,
    private val transcriptPersister: VoiceTranscriptPersister = VoiceTranscriptPersister(),
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
    private val recordWriter = HermesToolRecordWriter()
    private val queueStore = HermesQueueStore(
        conversationStore = conversationStore,
        writer = recordWriter,
        transcriptPersister = transcriptPersister,
        persistenceSessionId = ::currentPersistenceSessionId,
    )
    private val reducer = HermesJobReducer(
        pollIntervalMs = pollIntervalMs,
        pollRetryDelayMs = pollRetryDelayMs,
    )
    private val activeJobs = mutableMapOf<String, JobActor>()
    private val toolCalls = mutableMapOf<String, VoiceToolStatus>()
    private val _toolStatus = MutableStateFlow<VoiceToolStatus>(VoiceToolStatus.Idle)
    val toolStatus: StateFlow<VoiceToolStatus> = _toolStatus
    private var bridgeAttachment: BridgeAttachment? = null

    fun submit(callId: String, prompt: String): Boolean {
        return submit(callId = callId, prompt = prompt, activeKey = callActiveKey(callId))
    }

    fun submit(callId: String, prompt: String, activeKey: String): Boolean {
        val actor = synchronized(lock) {
            if (activeJobs.containsKey(activeKey)) return false
            JobActor(activeKey = activeKey, callId = callId, prompt = prompt).also {
                activeJobs[activeKey] = it
            }
        }
        launchActor(actor, JobEvent.Start)
        return true
    }

    fun resumeActiveJobs() {
        queueStore.activeRecords()
            .forEach { record ->
                val activeKey = record.activeKey()
                if (synchronized(lock) { hasActiveJob(record = record, activeKey = activeKey) }) return@forEach
                val jobId = record.jobId
                if (jobId == null) {
                    scope.launch(dispatcher) {
                        failDurableRecord(record, MISSING_JOB_ID_MESSAGE)
                    }
                    return@forEach
                }
                val startedAtMs = record.createdAt.toEpochMillisOrNull()
                    ?: record.updatedAt.toEpochMillisOrNull()
                    ?: run {
                        scope.launch(dispatcher) {
                            failDurableRecord(record, INVALID_TIMESTAMP_MESSAGE)
                        }
                        return@forEach
                    }
                val actor = synchronized(lock) {
                    if (hasActiveJob(record = record, activeKey = activeKey)) return@forEach
                    JobActor(
                        activeKey = activeKey,
                        callId = record.callId,
                        prompt = record.prompt,
                        startedAtMs = startedAtMs,
                    ).also {
                        it.jobId = jobId
                        activeJobs[activeKey] = it
                    }
                }
                launchActor(
                    actor,
                    JobEvent.Resume(jobId = jobId, stillWorkingAnnounced = record.stillWorkingAnnounced),
                )
            }
    }

    /** Durable failure for records that cannot be resumed (no live actor, no race). */
    private suspend fun failDurableRecord(record: HermesQueueRecord, message: String) {
        val persisted = queueStore.persistTerminal(
            callId = record.callId,
            prompt = record.prompt,
            status = VoiceToolRecordStatus.Expired(message),
            jobId = record.jobId,
            announced = null,
        )
        if (!persisted) return
        recordEventSafely(
            name = "voicelab.mobile.hermes_tool.failed",
            attributes = mapOf(
                "callId" to record.callId,
                "jobId" to record.jobId,
                "gemini.tool_call.call_id" to record.callId,
                "hermes_job_id" to record.jobId,
                "hermes_job_status" to HermesQueueStatus.Expired.wireName,
                "status" to HermesQueueStatus.Expired.wireName,
                "message" to message,
            ),
        )
        safeRecordDiagnostic(
            "hermes_job_failed",
            "callId=${record.callId}${record.jobId?.let { ", jobId=$it" }.orEmpty()}, message=$message",
        )
        safeOnJobFailed(
            HermesJobFailure(callId = record.callId, jobId = record.jobId, message = message, elapsedMs = 0L)
        )
        safeWriteQueueEvent(
            buildQueueEvent(
                type = "job_failed",
                callId = record.callId,
                jobId = record.jobId ?: "none",
                status = HermesQueueStatus.Expired.wireName,
            )
        )
        updateToolStatus(
            record.callId,
            VoiceToolStatus.HermesFailed(callId = record.callId, message = message),
        )
    }

    private fun hasActiveJob(record: HermesQueueRecord, activeKey: String): Boolean {
        if (activeJobs.containsKey(activeKey)) return true
        return activeJobs.values.any { actor ->
            when {
                record.jobId != null -> actor.jobId == record.jobId
                else -> actor.callId == record.callId && actor.jobId == null
            }
        }
    }

    suspend fun awaitJobs() {
        while (true) {
            val consumers = synchronized(lock) {
                if (activeJobs.isEmpty()) return
                activeJobs.values.mapNotNull { it.consumer }
            }
            if (consumers.isEmpty()) {
                // activeJobs is non-empty but no consumer is published yet: an actor was
                // inserted under `lock` and its consumer is being handed off just outside it.
                // yield() instead of re-looping immediately so we don't hot-spin in that window.
                yield()
                continue
            }
            consumers.joinAll()
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
        attachBridge(bridge = bridge, sessionId = UNBOUND_BRIDGE_SESSION_ID)
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

    fun cancel(callId: String, activeKey: String?, userInitiated: Boolean = false) {
        val origin = if (userInitiated) CancelOrigin.User else CancelOrigin.Gemini
        val actor = synchronized(lock) {
            activeKey?.let { activeJobs[it] } ?: activeJobs.values.lastOrNull { it.callId == callId }
        }
        if (actor != null) {
            // Live job: cancellation is delivered as an event and nothing more. The
            // reducer's cancel row is the single point that aborts the in-flight request,
            // persists the canceled record, cancels the remote job, and terminates. There
            // is deliberately no eager persist here: that would reintroduce a second
            // cancellation-persistence site, the exact duplication this actor model
            // deletes. Durability-before-inspection is the drain's job — callers that must
            // observe the terminal write await it through awaitJobs() (which the coordinator
            // folds into its close/drain path and tests await via awaitToolJobs()).
            // Invariant making the silent trySend drop safe: every path into Terminal commits
            // its terminal persist before events.close(), so if the channel is already closed
            // the job is durably terminal and a dropped CancelRequested is exactly equivalent
            // to the old terminal-freeze no-op — there is nothing left to cancel.
            actor.events.trySend(JobEvent.CancelRequested(origin))
            return
        }
        // Orphaned durable record (no live writer, so a direct write cannot race).
        val persistedRecord = queueStore.latestCancelCandidate(
            callId = callId,
            requireUnsubmitted = activeKey != null,
        )
        if (persistedRecord?.status?.isTerminal != false) return
        scope.launch(dispatcher) {
            val canceled = queueStore.persistCanceled(
                callId = callId,
                prompt = persistedRecord.prompt,
                jobId = persistedRecord.jobId,
                message = HERMES_CANCELED_MESSAGE,
                announced = userInitiated,
            )
            if (canceled) {
                updateToolStatus(
                    callId,
                    VoiceToolStatus.HermesFailed(callId = callId, message = HERMES_CANCELED_MESSAGE),
                )
                persistedRecord.jobId?.let { cancelRemoteJob(it) }
            }
        }
    }

    fun pendingRequests(): List<PendingHermesRequest> =
        queueStore.activeRecords()
            .map { PendingHermesRequest(callId = it.callId, jobId = it.jobId, prompt = it.prompt) }

    fun cancelByUser(request: PendingHermesRequest) {
        cancel(callId = request.callId, activeKey = null, userInitiated = true)
    }

    /**
     * Resolves which pending request a cancel_hermes question refers to. Matching
     * policy (moved verbatim from the coordinator): with at most one pending request
     * the question always refers to it; otherwise match by bidirectional substring
     * containment after normalization. On a unique match this also performs the
     * user-initiated cancel before returning [CancelHermesOutcome.Canceled] —
     * resolution and action keep one owner.
     */
    fun resolveAndCancelRequest(question: String): CancelHermesOutcome {
        val pending = pendingRequests()
        if (pending.isEmpty()) return CancelHermesOutcome.NothingPending
        val normalizedQuestion = question.normalizeForHermesMatch()
        val matches = when {
            pending.size <= 1 -> pending
            else -> pending.filter { request ->
                val prompt = request.prompt.normalizeForHermesMatch()
                prompt.contains(normalizedQuestion) || normalizedQuestion.contains(prompt)
            }
        }
        return when {
            matches.size == 1 -> {
                val request = matches.single()
                cancelByUser(request)
                recordDiagnostic("hermes_user_cancel", "callId=${request.callId}")
                CancelHermesOutcome.Canceled(request)
            }
            matches.isEmpty() -> CancelHermesOutcome.NoMatch(pending)
            else -> CancelHermesOutcome.Ambiguous(matches)
        }
    }

    private fun String.normalizeForHermesMatch(): String =
        lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * Full cancel_hermes handling: resolve (and on a unique match cancel), then send
     * the outcome as the tool response through the bridge, inheriting the standard
     * send machinery (attachment guard, bridgeSendTimeoutMs, sanitized failure
     * diagnostics). The coordinator's only involvement is dispatching here.
     *
     * [sessionId] is the session the cancel_hermes call originated from. When it is
     * a concrete session (not [UNBOUND_BRIDGE_SESSION_ID]) the response is only sent
     * if the current bridge attachment belongs to that same session; a mismatch is
     * recorded as `error=session_mismatch` instead of answering through the wrong
     * session. An unbound [sessionId] skips validation, matching the semantics of
     * the session-less bridge attach.
     */
    fun handleCancelHermesCall(callId: String, question: String, sessionId: Long) {
        val outcome = resolveAndCancelRequest(question)
        scope.launch(dispatcher) {
            var failureReason = "send_returned_false"
            var attachmentChangedAfterDelivery = false
            val attachment = currentBridgeAttachment()
            val sent = if (attachment == null) {
                failureReason = "no_bridge_attached"
                false
            } else if (sessionId != UNBOUND_BRIDGE_SESSION_ID && attachment.sessionId != sessionId) {
                failureReason = "session_mismatch"
                false
            } else {
                try {
                    withTimeoutOrNull(bridgeSendTimeoutMs) {
                        if (!attachment.isCurrentBridgeAttachment()) {
                            failureReason = "bridge_attachment_changed"
                            return@withTimeoutOrNull false
                        }
                        val delivered = attachment.bridge.sendCancelResponse(
                            callId = callId,
                            outcome = outcome,
                            sessionId = attachment.sessionId,
                        )
                        // Re-validate after the send: a detach/re-attach while the bridge
                        // call was suspended means the response raced a session change. A
                        // delivered response still counts as sent — the change is surfaced
                        // as an attachmentChanged advisory on the sent diagnostic. Only an
                        // undelivered send with a changed attachment is labeled
                        // bridge_attachment_changed.
                        if (!attachment.isCurrentBridgeAttachment()) {
                            if (delivered) {
                                attachmentChangedAfterDelivery = true
                            } else {
                                failureReason = "bridge_attachment_changed"
                            }
                        }
                        delivered
                    } ?: run {
                        failureReason = "send_timeout"
                        false
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failureReason = HermesTelemetryLogSanitizer.failureMessage(
                        error.message ?: error.javaClass.simpleName
                    )
                    false
                }
            }
            val sessionDetail = when (attachment?.sessionId) {
                null -> "none"
                UNBOUND_BRIDGE_SESSION_ID -> "unbound"
                else -> attachment.sessionId.toString()
            }
            if (sent) {
                recordDiagnostic(
                    "cancel_hermes_tool_response_sent",
                    "callId=$callId, sessionId=$sessionDetail" +
                        if (attachmentChangedAfterDelivery) ", attachmentChanged=true" else "",
                )
            } else {
                recordDiagnostic(
                    "cancel_hermes_tool_response_failed",
                    "callId=$callId, sessionId=$sessionDetail, error=$failureReason",
                )
            }
        }
    }

    private fun launchActor(actor: JobActor, initialEvent: JobEvent) {
        actor.events.trySend(initialEvent)
        val consumer = scope.launch(dispatcher) {
            for (event in actor.events) {
                val transition = reducer.reduce(actor.state, event)
                actor.state = transition.state
                (transition.state as? JobState.Polling)?.let { actor.jobId = it.jobId }
                transition.effects.forEach { effect ->
                    // Effect-failure containment: a persistence throw (e.g. SQLite IO from the
                    // production store) must never kill the consumer or escape to the scope's
                    // (handler-less) uncaught path and crash the app. Catch it, log through the
                    // diagnostics path, and keep draining — state was already committed by the
                    // reducer, so the loop stays consistent. CancellationException is coroutine
                    // cancellation, not an effect failure, and is rethrown untouched.
                    try {
                        execute(actor, effect)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        safeRecordDiagnostic(
                            "hermes_effect_failed",
                            "callId=${actor.callId}, effect=${effect::class.simpleName}, " +
                                "error=${HermesTelemetryLogSanitizer.failureMessage(e.message ?: e.javaClass.simpleName)}",
                        )
                    }
                }
                if (transition.state is JobState.Terminal) {
                    actor.events.close()
                }
            }
        }
        actor.consumer = consumer
        consumer.invokeOnCompletion {
            actor.stillWorkingTimer?.cancel()
            actor.inFlight?.cancel()
            actor.events.close()
            synchronized(lock) {
                if (activeJobs[actor.activeKey] === actor) {
                    activeJobs.remove(actor.activeKey)
                }
            }
        }
    }

    private suspend fun execute(actor: JobActor, effect: JobEffect) {
        when (effect) {
            JobEffect.PersistPending -> {
                val persisted = queueStore.persistPending(callId = actor.callId, prompt = actor.prompt)
                if (persisted) {
                    recordEventSafely(
                        name = "voicelab.mobile.hermes_tool.submitted",
                        attributes = mapOf(
                            "callId" to actor.callId,
                            "gemini.tool_call.call_id" to actor.callId,
                        ) + voiceTextPayload(key = "gemini.tool_call.prompt", text = actor.prompt),
                    )
                }
            }

            JobEffect.StartSubmit -> {
                actor.inFlight = scope.launch(dispatcher) {
                    val submitted = try {
                        withTimeoutOrNull(actor.remainingMs(maxElapsedMs)) {
                            toolApi.submitHermesJob(callId = actor.callId, prompt = actor.prompt)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        actor.events.trySend(
                            JobEvent.SubmitFailed(error.message ?: error.javaClass.simpleName)
                        )
                        return@launch
                    }
                    if (submitted == null) {
                        actor.events.trySend(JobEvent.TimedOut)
                    } else {
                        actor.events.trySend(
                            JobEvent.SubmitReturned(
                                jobId = submitted.jobId,
                                status = submitted.status,
                                failureMessage = submitted.failure?.safeMessage,
                            )
                        )
                    }
                }
            }

            is JobEffect.SchedulePoll -> {
                actor.inFlight = scope.launch(dispatcher) {
                    delay(effect.delayMs)
                    if (actor.hasTimedOut(maxElapsedMs)) {
                        actor.events.trySend(JobEvent.TimedOut)
                        return@launch
                    }
                    val poll = try {
                        withTimeoutOrNull(actor.remainingMs(maxElapsedMs)) {
                            toolApi.getHermesJob(jobId = effect.jobId)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        actor.events.trySend(
                            JobEvent.PollFailed(
                                message = error.message ?: error.javaClass.simpleName,
                                terminal = error.isTerminalHermesPollFailure(),
                            )
                        )
                        return@launch
                    }
                    if (poll == null) {
                        actor.events.trySend(JobEvent.TimedOut)
                    } else {
                        actor.events.trySend(
                            JobEvent.PollReturned(
                                status = poll.status,
                                answer = poll.answer,
                                failureMessage = poll.failure?.safeMessage,
                                serverElapsedMs = poll.elapsedMs,
                            )
                        )
                    }
                }
            }

            JobEffect.AbortInFlight -> actor.inFlight?.cancel()

            JobEffect.ScheduleStillWorkingTimer -> {
                actor.stillWorkingTimer = scope.launch(dispatcher) {
                    delay((stillWorkingThresholdMs - actor.elapsedMs()).coerceAtLeast(0L))
                    actor.events.trySend(JobEvent.StillWorkingDue)
                }
            }

            JobEffect.CancelStillWorkingTimer -> actor.stillWorkingTimer?.cancel()

            JobEffect.SendQueuedAck -> {
                if (sendQueuedAcknowledgementIfAttached(callId = actor.callId)) {
                    actor.events.trySend(JobEvent.QueuedAckDelivered)
                }
            }

            is JobEffect.NoteJobCreated -> {
                safeRecordDiagnostic(
                    "hermes_job_created",
                    "callId=${actor.callId}, jobId=${effect.jobId}, status=${effect.status}",
                )
                safeWriteQueueEvent(
                    buildQueueEvent(
                        type = "job_created",
                        callId = actor.callId,
                        jobId = effect.jobId,
                        status = effect.status.wireName,
                    )
                )
            }

            is JobEffect.PersistActive -> {
                val recordStatus = when (effect.status) {
                    HermesJobStatus.Running -> VoiceToolRecordStatus.Running
                    else -> VoiceToolRecordStatus.Queued
                }
                val persisted = queueStore.persistActive(
                    callId = actor.callId,
                    prompt = actor.prompt,
                    status = recordStatus,
                    jobId = effect.jobId,
                )
                if (persisted) {
                    updateToolStatus(
                        actor.callId,
                        when (recordStatus) {
                            VoiceToolRecordStatus.Running -> VoiceToolStatus.CallingHermes(
                                callId = actor.callId,
                                elapsedMs = actor.elapsedMs(),
                            )
                            else -> VoiceToolStatus.QueuedHermes(callId = actor.callId, jobId = effect.jobId)
                        },
                    )
                }
            }

            is JobEffect.PersistTerminal -> executePersistTerminal(actor, effect)

            is JobEffect.CancelRemoteJob -> cancelRemoteJob(effect.jobId)

            is JobEffect.AnnounceCompletion ->
                announceCompletedResult(callId = actor.callId, jobId = effect.jobId)

            is JobEffect.AnnounceTerminal -> {
                val acknowledged = effect.ackDelivered ||
                    sendQueuedAcknowledgementIfAttached(callId = actor.callId)
                if (acknowledged) {
                    announceTerminalResult(callId = actor.callId, jobId = effect.jobId)
                }
            }

            is JobEffect.AnnounceStillWorking -> announceStillWorking(actor, effect.jobId)

            is JobEffect.NotifyPollFailure -> safeOnPollFailed(
                HermesPollFailure(
                    callId = actor.callId,
                    jobId = actor.jobId.orEmpty(),
                    attempt = effect.attempt,
                    message = effect.message,
                )
            )
        }
    }

    private suspend fun executePersistTerminal(actor: JobActor, effect: JobEffect.PersistTerminal) {
        effect.jobId?.let { actor.jobId = it } // keeps the mirror right for the cancel-during-submit adoption
        when (val kind = effect.kind) {
            is TerminalKind.Complete -> {
                val persisted = queueStore.persistTerminal(
                    callId = actor.callId,
                    prompt = actor.prompt,
                    status = VoiceToolRecordStatus.Complete(kind.answer),
                    jobId = effect.jobId,
                    announced = false,
                )
                if (!persisted) return
                val elapsedMs = actor.elapsedMs()
                safeOnJobCompleted(
                    HermesJobCompletion(
                        callId = actor.callId,
                        jobId = requireNotNull(effect.jobId),
                        answer = kind.answer,
                        elapsedMs = elapsedMs,
                        serverElapsedMs = kind.serverElapsedMs,
                    )
                )
                recordEventSafely(
                    name = "voicelab.mobile.hermes_tool.completed",
                    attributes = mapOf(
                        "callId" to actor.callId,
                        "jobId" to effect.jobId,
                        "gemini.tool_call.call_id" to actor.callId,
                        "hermes_job_id" to effect.jobId,
                        "hermes_job_status" to "succeeded",
                        "elapsedMs" to elapsedMs,
                        "serverElapsedMs" to kind.serverElapsedMs,
                    ) + voiceTextPayload(key = "hermes.response.answer", text = kind.answer),
                )
                safeRecordDiagnostic(
                    "hermes_job_completed",
                    "callId=${actor.callId}, jobId=${effect.jobId}, elapsedMs=$elapsedMs" +
                        "${kind.serverElapsedMs?.let { ", serverElapsedMs=$it" }.orEmpty()}, answerChars=${kind.answer.length}",
                )
                safeWriteQueueEvent(
                    buildQueueEvent(
                        type = "job_completed",
                        callId = actor.callId,
                        jobId = requireNotNull(effect.jobId),
                        status = "succeeded",
                        elapsedMs = elapsedMs,
                        serverElapsedMs = kind.serverElapsedMs,
                        answerChars = kind.answer.length,
                    )
                )
                safeWriteHermesAnswer(kind.answer)
                updateToolStatus(
                    actor.callId,
                    VoiceToolStatus.HermesAnswered(callId = actor.callId, elapsedMs = actor.elapsedMs()),
                )
            }

            is TerminalKind.Canceled -> {
                val persisted = queueStore.persistCanceled(
                    callId = actor.callId,
                    prompt = actor.prompt,
                    jobId = effect.jobId,
                    message = kind.message,
                    announced = kind.origin == CancelOrigin.User,
                )
                if (persisted && effect.emitFailureTelemetry) {
                    emitTerminalFailureTelemetry(actor, effect, kind.message)
                }
                if (persisted) {
                    updateToolStatus(
                        actor.callId,
                        VoiceToolStatus.HermesFailed(callId = actor.callId, message = kind.message),
                    )
                }
            }

            is TerminalKind.Failed -> persistFailureKind(
                actor, effect, VoiceToolRecordStatus.Failed(kind.message), kind.message,
            )

            is TerminalKind.Expired -> persistFailureKind(
                actor, effect, VoiceToolRecordStatus.Expired(kind.message), kind.message,
            )
        }
    }

    private suspend fun persistFailureKind(
        actor: JobActor,
        effect: JobEffect.PersistTerminal,
        status: VoiceToolRecordStatus,
        message: String,
    ) {
        val persisted = queueStore.persistTerminal(
            callId = actor.callId,
            prompt = actor.prompt,
            status = status,
            jobId = effect.jobId,
            announced = null,
        )
        if (!persisted) return
        if (effect.emitFailureTelemetry) {
            emitTerminalFailureTelemetry(actor, effect, message)
        }
        updateToolStatus(
            actor.callId,
            VoiceToolStatus.HermesFailed(callId = actor.callId, message = message),
        )
    }

    private fun emitTerminalFailureTelemetry(
        actor: JobActor,
        effect: JobEffect.PersistTerminal,
        message: String,
    ) {
        val statusWire = effect.kind.queueEventStatus()
        recordEventSafely(
            name = "voicelab.mobile.hermes_tool.failed",
            attributes = mapOf(
                "callId" to actor.callId,
                "jobId" to effect.jobId,
                "gemini.tool_call.call_id" to actor.callId,
                "hermes_job_id" to effect.jobId,
                "hermes_job_status" to statusWire,
                "status" to statusWire,
                "message" to message,
            ),
        )
        safeRecordDiagnostic(
            "hermes_job_failed",
            "callId=${actor.callId}${effect.jobId?.let { ", jobId=$it" }.orEmpty()}, message=$message",
        )
        safeOnJobFailed(
            HermesJobFailure(callId = actor.callId, jobId = effect.jobId, message = message, elapsedMs = 0L)
        )
        safeWriteQueueEvent(
            buildQueueEvent(
                type = "job_failed",
                callId = actor.callId,
                jobId = effect.jobId ?: "none",
                status = statusWire,
            )
        )
    }

    private fun TerminalKind.queueEventStatus(): String = when (this) {
        is TerminalKind.Complete -> HermesQueueStatus.Complete.wireName
        is TerminalKind.Failed -> HermesQueueStatus.Failed.wireName
        is TerminalKind.Expired -> HermesQueueStatus.Expired.wireName
        is TerminalKind.Canceled -> HermesQueueStatus.Canceled.wireName
    }

    private suspend fun announceStillWorking(actor: JobActor, jobId: String) {
        val current = currentBridgeAttachment() ?: return
        announcementMutex.withLock {
            val record = queueStore.latestRecord(callId = actor.callId, jobId = jobId)
                ?: return@withLock
            if (record.status.isTerminal || record.stillWorkingAnnounced) return@withLock
            if (!current.isCurrentBridgeAttachment()) return@withLock
            val sent = runCatching {
                withTimeoutOrNull(bridgeSendTimeoutMs) {
                    current.bridge.sendStillWorkingUpdate(
                        callId = actor.callId,
                        prompt = record.prompt,
                        sessionId = current.sessionId,
                    )
                } ?: false
            }.getOrDefault(false)
            if (sent) {
                queueStore.markStillWorkingAnnounced(callId = actor.callId, jobId = jobId)
                safeRecordDiagnostic(
                    "hermes_still_working_announced",
                    "callId=${actor.callId}, jobId=$jobId",
                )
            }
        }
    }

    private suspend fun sendQueuedAcknowledgementIfAttached(callId: String): Boolean {
        val attachment = currentBridgeAttachment() ?: return false
        return runCatching {
            withTimeoutOrNull(bridgeSendTimeoutMs) {
                if (!attachment.isCurrentBridgeAttachment()) return@withTimeoutOrNull false
                attachment.bridge.sendQueuedAcknowledgement(callId = callId, sessionId = attachment.sessionId)
            } ?: false
        }.getOrDefault(false)
    }

    private suspend fun announceUnannouncedTerminalResults(bridge: HermesSessionBridge, sessionId: Long) {
        queueStore.unannouncedTerminalRecords()
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
        val current = attachment ?: run {
            queueStore.appendVisibleResultMessageIfNeeded(callId = callId, jobId = jobId)
            return
        }
        announcementMutex.withLock {
            val record = queueStore.latestRecord(callId = callId, jobId = jobId)
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
            if (!sent) {
                queueStore.appendVisibleResultMessageIfNeeded(callId = callId, jobId = jobId)
            }
        }
    }

    private suspend fun announceTerminalResult(
        callId: String,
        jobId: String?,
        attachment: BridgeAttachment? = currentBridgeAttachment(),
    ) {
        val current = attachment ?: run {
            queueStore.appendVisibleResultMessageIfNeeded(callId = callId, jobId = jobId)
            return
        }
        announcementMutex.withLock {
            val record = queueStore.latestRecord(callId = callId, jobId = jobId)
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
            if (!sent) {
                queueStore.appendVisibleResultMessageIfNeeded(callId = callId, jobId = jobId)
            }
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

    private fun currentBridgeAttachment(): BridgeAttachment? = synchronized(lock) {
        bridgeAttachment
    }

    private fun BridgeAttachment.isCurrentBridgeAttachment(): Boolean = synchronized(lock) {
        active && bridgeAttachment?.bridge === bridge && bridgeAttachment?.sessionId == sessionId
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

    private class JobActor(
        val activeKey: String,
        val callId: String,
        val prompt: String,
        val startedAtMs: Long = System.currentTimeMillis(),
    ) {
        val events = Channel<JobEvent>(Channel.UNLIMITED)
        var state: JobState = JobState.Created

        // @Volatile so awaitJobs() reliably sees the consumer handoff that launchActor
        // performs outside `lock` after the actor is already published in activeJobs.
        @Volatile
        var consumer: Job? = null
        var inFlight: Job? = null
        var stillWorkingTimer: Job? = null
        @Volatile
        var jobId: String? = null // read-only mirror for hasActiveJob/cancel lookup

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

    companion object {
        /**
         * Sentinel session id for a bridge attachment that is not scoped to a concrete
         * Gemini session. Cancel sends with an unbound expected session skip session
         * validation, matching the semantics of the session-less attach overload.
         */
        const val UNBOUND_BRIDGE_SESSION_ID = 0L
        private const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        private const val DEFAULT_POLL_RETRY_DELAY_MS = 1_000L
        private const val DEFAULT_MAX_ELAPSED_MS = 24L * 60 * 60 * 1000
        private const val MISSING_JOB_ID_MESSAGE = "Hermes job was missing a job id."
        private const val INVALID_TIMESTAMP_MESSAGE = "Hermes job had invalid timing metadata."
        private const val DEFAULT_REMOTE_CANCEL_TIMEOUT_MS = 5_000L
        private const val DEFAULT_BRIDGE_SEND_TIMEOUT_MS = 30_000L
        private const val DEFAULT_STILL_WORKING_THRESHOLD_MS = 45_000L
    }
}
