package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.VoicePlaybackEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.gemini.voiceToolSpecsByName
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesJobSnapshot
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesJobStatus
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesJobSubmitResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileVoiceSessionResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.VoiceFailure
import me.rerere.rikkahub.voiceagent.hermesvoice.VoiceFailureKind
import me.rerere.rikkahub.voiceagent.hermesvoice.VoiceFailureSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

fun voiceToolCall(callId: String, name: String, arg: String): GeminiLiveEvent.ToolCall =
    voiceToolSpecsByName[name]?.buildCall?.invoke(callId, arg)
        ?: error("voiceToolCall: unknown tool name '$name' — construct GeminiLiveEvent.UnsupportedToolCall directly for negative-path fixtures")

internal fun hermesJobSnapshotFixture(
    jobId: String? = null,
    callId: String? = null,
    status: HermesJobStatus = HermesJobStatus.Queued,
    answer: String? = null,
    model: String? = null,
    profileId: String? = null,
    profileLabel: String? = null,
    elapsedMs: Long? = null,
    failure: VoiceFailure? = null,
    createdAt: String? = null,
    completedAt: String? = null,
    prompt: String? = null,
    updatedAt: String? = null,
): HermesJobSnapshot = HermesJobSnapshot(
    jobId = jobId.orEmpty(),
    callId = callId,
    prompt = prompt,
    status = status,
    createdAt = createdAt.orEmpty(),
    updatedAt = updatedAt,
    completedAt = completedAt,
    answer = answer,
    model = model,
    profileId = profileId,
    profileLabel = profileLabel,
    elapsedMs = elapsedMs,
    failure = failure,
)

internal fun hermesFailureFixture(
    message: String,
    kind: VoiceFailureKind,
): VoiceFailure = VoiceFailure(
    kind = kind,
    safeMessage = message,
    safeSummary = message,
    retryable = false,
    source = VoiceFailureSource.HermesVoice,
)

class FakeGeminiLiveVoiceClient : GeminiLiveVoiceClient {
    private val recordedAudioMessages = mutableListOf<String>()
    private val recordedAudioStreamEndSessionIds = mutableListOf<Long?>()
    private val recordedToolResponses = mutableListOf<Pair<String, String>>()
    private val recordedToolResponseNames = mutableListOf<String>()
    private val recordedToolResponseSessionIds = mutableListOf<Long?>()
    private val recordedTextTurns = mutableListOf<Pair<Long?, String>>()
    val failToolResponses = mutableSetOf<String>()
    val toolResponseErrors = mutableMapOf<String, Throwable>()
    var failTextTurns = false
    private var recordedCloseCalls = 0
    var onBeforeToolResponseRecorded: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var connectEvent: GeminiLiveEvent? = null
    var activateOutboundSessionEvent: GeminiLiveEvent? = null
    var connectedToken: String? = null
    var connectedWebsocketUrl: String? = null
    var connectedProviderModel: String? = null
    var connectedSystemInstruction: String? = null
    var connectedContextTurns: List<GeminiContentTurn> = emptyList()
    val eventHandlers = mutableListOf<(GeminiLiveEvent) -> Unit>()
    private val connected = CompletableDeferred<Unit>()
    private val blockedResponses = mutableMapOf<String, MutableList<BlockedToolResponse>>()
    private val blockedConnectCompletions = mutableListOf<BlockedConnect>()
    private val outboundSendLock = Any()
    private var outboundSessionId: Long? = null

    val audioMessages: List<String>
        get() = synchronized(outboundSendLock) { recordedAudioMessages.toList() }

    val audioStreamEndSessionIds: List<Long?>
        get() = synchronized(outboundSendLock) { recordedAudioStreamEndSessionIds.toList() }

    val toolResponses: List<Pair<String, String>>
        get() = synchronized(outboundSendLock) { recordedToolResponses.toList() }

    val toolResponseNames: List<String>
        get() = synchronized(outboundSendLock) { recordedToolResponseNames.toList() }

    val toolResponseSessionIds: List<Long?>
        get() = synchronized(outboundSendLock) { recordedToolResponseSessionIds.toList() }

    val textTurns: List<Pair<Long?, String>>
        get() = synchronized(outboundSendLock) { recordedTextTurns.toList() }

    val closeCalls: Int
        get() = synchronized(outboundSendLock) { recordedCloseCalls }

    fun blockToolResponse(callId: String): BlockedToolResponse {
        return blockNextToolResponse(callId)
    }

    fun blockNextToolResponse(callId: String): BlockedToolResponse {
        return BlockedToolResponse().also { blocked ->
            synchronized(blockedResponses) {
                blockedResponses.getOrPut(callId) { mutableListOf() } += blocked
            }
        }
    }

    fun blockNextConnectCompletion(): BlockedConnect {
        return BlockedConnect().also { blocked ->
            synchronized(blockedConnectCompletions) {
                blockedConnectCompletions += blocked
            }
        }
    }

    override suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    ) {
        connectedToken = token
        connectedWebsocketUrl = websocketUrl
        connectedProviderModel = providerModel
        connectedSystemInstruction = systemInstruction
        connectedContextTurns = contextTurns
        eventHandlers += onEvent
        connected.complete(Unit)
        connectEvent?.let(onEvent)
        val blocked = synchronized(blockedConnectCompletions) {
            blockedConnectCompletions.removeFirstOrNull()
        }
        blocked?.release?.await()
    }

    override fun sendAudio(base64Pcm16: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            recordedAudioMessages += base64Pcm16
            return true
        }
    }

    override fun activateOutboundSession(sessionId: Long) {
        synchronized(outboundSendLock) {
            outboundSessionId = sessionId
        }
        activateOutboundSessionEvent?.let { event ->
            eventHandlers.lastOrNull()?.invoke(event)
        }
    }

    override fun sendAudioStreamEnd(sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            recordedAudioStreamEndSessionIds += sessionId
            return true
        }
    }

    override fun invalidateOutboundSession() {
        synchronized(outboundSendLock) {
            outboundSessionId = null
        }
    }

    suspend fun awaitConnect() {
        withTimeout(500) {
            connected.await()
        }
    }

    suspend fun awaitConnectCount(count: Int) {
        withTimeout(500) {
            while (eventHandlers.size < count) {
                delay(10)
            }
        }
    }

    override fun sendToolResponse(
        callId: String,
        answer: String,
        sessionId: Long?,
        name: String,
    ): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
        }
        val blocked = synchronized(blockedResponses) {
            blockedResponses[callId]?.removeFirstOrNull()
        }
        if (blocked != null) {
            // Wait OUTSIDE outboundSendLock. The bridge send path is suspend-based now
            // (no runInterruptible thread-interrupt can break a stuck wait anymore), so a
            // blocked in-flight send must not hold the outbound monitor: otherwise
            // invalidateOutboundSession() and concurrent sends would be forced to wait out
            // the full latch timeout.
            blocked.started.countDown()
            blocked.release.await(blocked.timeoutMillis, TimeUnit.MILLISECONDS)
        }
        val beforeRecord = synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            onBeforeToolResponseRecorded
        }
        beforeRecord?.invoke()
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            toolResponseErrors[callId]?.let { throw it }
            if (callId in failToolResponses) {
                return false
            }
            recordedToolResponses += callId to answer
            recordedToolResponseNames += name
            recordedToolResponseSessionIds += sessionId
            return true
        }
    }

    override fun sendTextTurn(text: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            if (failTextTurns) {
                return false
            }
            recordedTextTurns += sessionId to text
            return true
        }
    }

    override fun close() {
        synchronized(outboundSendLock) {
            outboundSessionId = null
            recordedCloseCalls += 1
        }
        onClose?.invoke()
    }
}

class BlockedToolResponse {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    var timeoutMillis: Long = 500
}

class BlockedConnect {
    val release = CompletableDeferred<Unit>()
}

class BlockedSubmit {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    var timeoutMillis: Long = 500
}

class BlockedCancellableSubmit {
    val started = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
}

class BlockedCancel {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
}

class FakeVoiceToolApi : VoiceToolApi {
    val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val lock = Any()
    private val calls = mutableMapOf<String, PendingHermesJob>()
    private val scriptedPolls = mutableMapOf<String, ArrayDeque<Any>>()
    private val pollRequests = mutableListOf<String>()
    private val submitFailures = mutableMapOf<String, Throwable>()
    private val submitResponses = mutableMapOf<String, MobileHermesJobSubmitResponse>()
    private val blockedSubmissions = mutableMapOf<String, MutableList<BlockedSubmit>>()
    private val blockedCancellableSubmissions = mutableMapOf<String, MutableList<BlockedCancellableSubmit>>()
    private val blockedCancellations = mutableMapOf<String, MutableList<BlockedCancel>>()
    private var jobCounter = 0

    override suspend fun submitHermesJob(callId: String, prompt: String): MobileHermesJobSubmitResponse {
        synchronized(lock) {
            submitFailures.remove(callId)?.let { throw it }
        }
        val job = synchronized(lock) {
            requests += callId to prompt
            val jobId = "job-${++jobCounter}"
            PendingHermesJob(callId = callId, prompt = prompt, jobId = jobId).also {
                calls[jobId] = it
            }
        }
        job.request.complete(callId to prompt)
        val blocked = synchronized(blockedSubmissions) {
            blockedSubmissions[callId]?.removeFirstOrNull()
        }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(blocked.timeoutMillis, TimeUnit.MILLISECONDS)
        }
        val cancellableBlocked = synchronized(blockedCancellableSubmissions) {
            blockedCancellableSubmissions[callId]?.removeFirstOrNull()
        }
        if (cancellableBlocked != null) {
            cancellableBlocked.started.countDown()
            try {
                cancellableBlocked.release.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                job.cancelled.complete(Unit)
                throw error
            }
        }
        synchronized(lock) {
            submitResponses.remove(callId)?.let { response ->
                return response.copy(jobId = job.jobId, callId = callId)
            }
        }
        return hermesJobSnapshotFixture(
            jobId = job.jobId,
            callId = callId,
            status = HermesJobStatus.Queued,
            createdAt = "2026-06-11T00:00:00.000Z",
        )
    }

    override suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse {
        val job = synchronized(lock) { calls.getValue(jobId) }
        try {
            while (true) {
                val scripted = synchronized(lock) {
                    pollRequests += jobId
                    scriptedPolls[jobId]?.removeFirstOrNull()
                }
                when (scripted) {
                    is MobileHermesJobPollResponse -> return scripted
                    is Throwable -> throw scripted
                }
                if (job.result.isCompleted) {
                    return job.result.await()
                }
                delay(10)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            job.cancelled.complete(Unit)
            throw error
        }
        error("unreachable")
    }

    override suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse {
        val job = synchronized(lock) { calls.getValue(jobId) }
        val blocked = synchronized(blockedCancellations) {
            blockedCancellations[job.callId]?.removeFirstOrNull()
        }
        if (blocked != null) {
            blocked.started.complete(Unit)
            try {
                blocked.release.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                blocked.cancelled.complete(Unit)
                throw error
            }
        }
        job.remoteCancelled.complete(Unit)
        job.result.cancel()
        return hermesJobSnapshotFixture(
            jobId = job.jobId,
            callId = job.callId,
            status = HermesJobStatus.Failed,
            failure = hermesFailureFixture(
                message = "Hermes job canceled",
                kind = VoiceFailureKind.HermesFailed,
            ),
            createdAt = "2026-06-11T00:00:00.000Z",
            completedAt = "2026-06-11T00:00:01.000Z",
        )
    }

    suspend fun awaitRequest(callId: String? = null): Pair<String, String> = withTimeout(500) {
        while (true) {
            val job = synchronized(lock) {
                if (callId == null) calls.values.firstOrNull()
                else calls.values.firstOrNull { it.callId == callId }
            }
            if (job != null) return@withTimeout job.request.await()
            delay(10)
        }
        error("unreachable")
    }

    fun complete(response: MobileHermesResponse) {
        val job = call(response.callId)
        job.result.complete(
            hermesJobSnapshotFixture(
                jobId = job.jobId,
                callId = response.callId,
                status = HermesJobStatus.Succeeded,
                answer = response.answer,
                model = response.model,
                profileId = response.profileId,
                profileLabel = response.profileLabel,
                elapsedMs = response.elapsedMs,
                createdAt = "2026-06-11T00:00:00.000Z",
                completedAt = "2026-06-11T00:00:01.000Z",
            )
        )
    }

    fun fail(error: Throwable) {
        firstCall().result.completeExceptionally(error)
    }

    fun fail(callId: String, error: Throwable) {
        call(callId).result.completeExceptionally(error)
    }

    fun failJob(callId: String, message: String) {
        val job = call(callId)
        job.result.complete(
            hermesJobSnapshotFixture(
                jobId = job.jobId,
                callId = callId,
                status = HermesJobStatus.Failed,
                failure = hermesFailureFixture(
                    message = message,
                    kind = VoiceFailureKind.HermesFailed,
                ),
                createdAt = "2026-06-11T00:00:00.000Z",
                completedAt = "2026-06-11T00:00:01.000Z",
            )
        )
    }

    fun expireJob(callId: String, message: String? = null) {
        val job = call(callId)
        job.result.complete(
            hermesJobSnapshotFixture(
                jobId = job.jobId,
                callId = callId,
                status = HermesJobStatus.Expired,
                failure = message?.let {
                    hermesFailureFixture(
                        message = it,
                        kind = VoiceFailureKind.Expired,
                    )
                },
                createdAt = "2026-06-11T00:00:00.000Z",
                completedAt = "2026-06-11T00:00:01.000Z",
            )
        )
    }

    fun scriptPoll(callId: String, response: MobileHermesJobPollResponse) {
        val job = call(callId)
        synchronized(lock) {
            scriptedPolls.getOrPut(job.jobId) { ArrayDeque() } += response
        }
    }

    fun scriptPollSucceeded(jobId: String, callId: String, answer: String) {
        synchronized(lock) {
            calls.getOrPut(jobId) {
                PendingHermesJob(callId = callId, prompt = "", jobId = jobId)
            }
            scriptedPolls.getOrPut(jobId) { ArrayDeque() } += hermesJobSnapshotFixture(
                jobId = jobId,
                callId = callId,
                status = HermesJobStatus.Succeeded,
                answer = answer,
                createdAt = "2026-06-11T00:00:00.000Z",
                completedAt = "2026-06-11T00:00:01.000Z",
            )
        }
    }

    fun seedJob(jobId: String, callId: String) {
        synchronized(lock) {
            calls.getOrPut(jobId) {
                PendingHermesJob(callId = callId, prompt = "", jobId = jobId)
            }
        }
    }

    fun scriptPollFailure(callId: String, error: Throwable) {
        val job = call(callId)
        synchronized(lock) {
            scriptedPolls.getOrPut(job.jobId) { ArrayDeque() } += error
        }
    }

    fun failSubmit(callId: String, error: Throwable) {
        synchronized(lock) {
            submitFailures[callId] = error
        }
    }

    fun scriptSubmitStatus(callId: String, status: String) {
        synchronized(lock) {
            val parsedStatus = HermesJobStatus.parse(status)
            submitResponses[callId] = hermesJobSnapshotFixture(
                jobId = "",
                callId = callId,
                status = parsedStatus ?: HermesJobStatus.Failed,
                failure = if (parsedStatus == null) {
                    hermesFailureFixture(
                        message = "Unknown Hermes job status: $status",
                        kind = VoiceFailureKind.Internal,
                    )
                } else {
                    null
                },
                createdAt = "2026-06-11T00:00:00.000Z",
            )
        }
    }

    fun blockSubmit(callId: String): BlockedSubmit {
        return BlockedSubmit().also { blocked ->
            synchronized(blockedSubmissions) {
                blockedSubmissions.getOrPut(callId) { mutableListOf() } += blocked
            }
        }
    }

    fun blockSubmitCancellable(callId: String): BlockedCancellableSubmit {
        return BlockedCancellableSubmit().also { blocked ->
            synchronized(blockedCancellableSubmissions) {
                blockedCancellableSubmissions.getOrPut(callId) { mutableListOf() } += blocked
            }
        }
    }

    fun blockCancel(callId: String): BlockedCancel {
        return BlockedCancel().also { blocked ->
            synchronized(blockedCancellations) {
                blockedCancellations.getOrPut(callId) { mutableListOf() } += blocked
            }
        }
    }

    fun scriptQueuedPolls(callId: String, count: Int) {
        repeat(count) {
            val job = call(callId)
            scriptPoll(
                callId = callId,
                response = hermesJobSnapshotFixture(
                    jobId = job.jobId,
                    callId = callId,
                    status = HermesJobStatus.Queued,
                    createdAt = "2026-06-11T00:00:00.000Z",
                ),
            )
        }
    }

    suspend fun awaitCancelled(callId: String) {
        withTimeout(500) {
            call(callId).cancelled.await()
        }
    }

    suspend fun awaitRemoteCancelled(callId: String) {
        withTimeout(500) {
            call(callId).remoteCancelled.await()
        }
    }

    suspend fun awaitRemoteCancelledJob(jobId: String) {
        withTimeout(500) {
            synchronized(lock) { calls.getValue(jobId) }.remoteCancelled.await()
        }
    }

    fun wasCancelled(callId: String): Boolean = call(callId).cancelled.isCompleted

    fun wasRemoteCancelled(callId: String): Boolean = call(callId).remoteCancelled.isCompleted

    fun pollCount(callId: String): Int = synchronized(lock) {
        val jobIds = calls.values.filter { it.callId == callId }.map { it.jobId }.toSet()
        pollRequests.count { it in jobIds }
    }

    private fun call(callId: String): PendingHermesJob = synchronized(lock) {
        calls.values.firstOrNull { it.callId == callId && !it.result.isCompleted && !it.cancelled.isCompleted }
            ?: calls.values.lastOrNull { it.callId == callId }
            ?: PendingHermesJob(callId = callId, prompt = "", jobId = "job-${++jobCounter}").also {
                calls[it.jobId] = it
            }
    }

    private fun firstCall(): PendingHermesJob = synchronized(lock) {
        calls.values.firstOrNull { !it.result.isCompleted && !it.cancelled.isCompleted }
            ?: PendingHermesJob(callId = "", prompt = "", jobId = "job-${++jobCounter}").also {
                calls[it.jobId] = it
            }
    }
}

class PendingHermesJob(
    val callId: String,
    val prompt: String,
    val jobId: String,
) {
    val request = CompletableDeferred<Pair<String, String>>()
    val result = CompletableDeferred<MobileHermesJobPollResponse>()
    val cancelled = CompletableDeferred<Unit>()
    val remoteCancelled = CompletableDeferred<Unit>()
}

class FakeVoiceAudioEngine : VoiceAudioEngine {
    val playedPcm16 = CopyOnWriteArrayList<String>()
    var suppressPlaybackCalls = 0
    var releaseCalls = 0
    var startCaptureCalls = 0
    var stopCaptureCalls = 0
    var startCaptureError: Throwable? = null
    private var errorHandler: ((String) -> Unit)? = null
    private var playbackSessionId: Long? = null
    private var playbackEventHandler: ((VoicePlaybackEvent) -> Unit)? = null
    private var nextPlaybackEpoch = 0L
    private var acceptingPlaybackEpoch: Long? = null
    private val pendingDrainEpochs = ArrayDeque<Long>()
    var markPlaybackTurnCompleteCalls = 0
        private set
    private var captureCallback: ((ByteArray) -> Unit)? = null
    private var debugInjectionCompleteCallback: (() -> Unit)? = null
    private val blockedStartCaptures = mutableListOf<BlockedPlayback>()
    private val blockedStopCaptures = mutableListOf<BlockedPlayback>()
    private val blockedPlaybacks = mutableListOf<BlockedPlayback>()
    private val blockedAfterAcceptedPlaybacks = mutableListOf<BlockedPlayback>()
    private val blockedSuppressions = mutableListOf<BlockedPlayback>()
    private val blockedTurnCompletions = mutableListOf<BlockedPlayback>()

    override fun setErrorHandler(onError: ((String) -> Unit)?) {
        errorHandler = onError
    }

    override fun setPlaybackEventHandler(onEvent: ((VoicePlaybackEvent) -> Unit)?) {
        playbackEventHandler = onEvent
    }

    override fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) {
        startCaptureCalls += 1
        startCaptureError?.let { throw it }
        val blocked = synchronized(blockedStartCaptures) { blockedStartCaptures.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        captureCallback = onPcm16
        debugInjectionCompleteCallback = onDebugInjectionComplete
    }

    override fun stopCapture() {
        val blocked = synchronized(blockedStopCaptures) { blockedStopCaptures.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        stopCaptureCalls += 1
        captureCallback = null
        debugInjectionCompleteCallback = null
    }

    override fun playPcm16(base64Pcm16: String, sessionId: Long?): Boolean {
        if (sessionId != null && playbackSessionId != sessionId) {
            return false
        }
        val blocked = synchronized(blockedPlaybacks) { blockedPlaybacks.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        if (sessionId != null && playbackSessionId != sessionId) {
            return false
        }
        playedPcm16 += base64Pcm16
        if (acceptingPlaybackEpoch == null) {
            nextPlaybackEpoch += 1
            acceptingPlaybackEpoch = nextPlaybackEpoch
            playbackEventHandler?.invoke(VoicePlaybackEvent.Active(nextPlaybackEpoch))
        }
        val blockedAfterAccepted = synchronized(blockedAfterAcceptedPlaybacks) {
            blockedAfterAcceptedPlaybacks.removeFirstOrNull()
        }
        if (blockedAfterAccepted != null) {
            blockedAfterAccepted.started.countDown()
            blockedAfterAccepted.release.await(500, TimeUnit.MILLISECONDS)
        }
        return true
    }

    override fun activatePlaybackSession(sessionId: Long) {
        val retiredEpochs = retirePlaybackEpochs()
        playbackSessionId = sessionId
        retiredEpochs.forEach { playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(it)) }
    }

    override fun invalidatePlaybackSession() {
        val retiredEpochs = retirePlaybackEpochs()
        playbackSessionId = null
        retiredEpochs.forEach { playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(it)) }
    }

    override fun suppressPlayback() {
        val blocked = synchronized(blockedSuppressions) { blockedSuppressions.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        suppressPlaybackCalls += 1
        retirePlaybackEpochs().forEach { playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(it)) }
    }

    override fun markPlaybackTurnComplete(sessionId: Long?): Boolean {
        markPlaybackTurnCompleteCalls += 1
        val blocked = synchronized(blockedTurnCompletions) {
            blockedTurnCompletions.removeFirstOrNull()
        }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        if (sessionId != null && playbackSessionId != sessionId) return false
        val epoch = acceptingPlaybackEpoch ?: return true
        acceptingPlaybackEpoch = null
        pendingDrainEpochs.addLast(epoch)
        playbackEventHandler?.invoke(VoicePlaybackEvent.DrainStarted(epoch))
        return true
    }

    override fun release() {
        val retiredEpochs = retirePlaybackEpochs()
        releaseCalls += 1
        retiredEpochs.forEach { playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(it)) }
        playbackEventHandler = null
    }

    fun completePlaybackDrain() {
        val epoch = pendingDrainEpochs.removeFirstOrNull() ?: return
        playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(epoch))
    }

    private fun retirePlaybackEpochs(): List<Long> = buildList {
        acceptingPlaybackEpoch?.let(::add)
        addAll(pendingDrainEpochs)
        acceptingPlaybackEpoch = null
        pendingDrainEpochs.clear()
    }

    fun blockNextPlayback(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedPlaybacks) {
                blockedPlaybacks += blocked
            }
        }
    }

    fun blockAfterNextAcceptedPlayback(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedAfterAcceptedPlaybacks) {
                blockedAfterAcceptedPlaybacks += blocked
            }
        }
    }

    fun blockNextStartCapture(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedStartCaptures) {
                blockedStartCaptures += blocked
            }
        }
    }

    fun blockNextStopCapture(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedStopCaptures) {
                blockedStopCaptures += blocked
            }
        }
    }

    fun blockNextSuppression(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedSuppressions) {
                blockedSuppressions += blocked
            }
        }
    }

    fun blockNextPlaybackTurnComplete(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedTurnCompletions) {
                blockedTurnCompletions += blocked
            }
        }
    }

    suspend fun awaitSuppressPlaybackCalls(count: Int) {
        withTimeout(500) {
            while (suppressPlaybackCalls < count) {
                delay(10)
            }
        }
    }

    fun emitCapture(pcm16: ByteArray) {
        captureCallback?.invoke(pcm16)
    }

    fun completeDebugInjection() {
        debugInjectionCompleteCallback?.invoke()
    }

    fun emitError(message: String) {
        errorHandler?.invoke(message)
    }
}

class BlockedPlayback {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
}

class FakeVoiceConversationStore(
    initialConversation: Conversation = Conversation.ofId(id = Uuid.random()),
) : VoiceConversationStore {
    private val updates = mutableListOf<Conversation>()
    private val blockedUpdates = mutableListOf<BlockedUpdate>()
    private val blockedAfterUpdates = mutableListOf<BlockedUpdate>()
    private val failedUpdates = mutableListOf<Throwable>()
    override val conversation: StateFlow<Conversation> = MutableStateFlow(initialConversation)

    override suspend fun update(transform: (Conversation) -> Conversation) {
        val blockedBeforeUpdate = synchronized(blockedUpdates) { blockedUpdates.removeFirstOrNull() }
        if (blockedBeforeUpdate != null) {
            blockedBeforeUpdate.started.countDown()
            blockedBeforeUpdate.release.await(500, TimeUnit.MILLISECONDS)
        }
        synchronized(failedUpdates) { failedUpdates.removeFirstOrNull() }?.let { throw it }
        val flow = conversation as MutableStateFlow<Conversation>
        flow.value = transform(flow.value)
        synchronized(updates) {
            updates += flow.value
        }
        val blockedAfterUpdate = synchronized(blockedAfterUpdates) { blockedAfterUpdates.removeFirstOrNull() }
        if (blockedAfterUpdate != null) {
            blockedAfterUpdate.started.countDown()
            blockedAfterUpdate.release.await(500, TimeUnit.MILLISECONDS)
        }
    }

    fun blockNextUpdate(): BlockedUpdate {
        return BlockedUpdate().also { blocked ->
            synchronized(blockedUpdates) {
                blockedUpdates += blocked
            }
        }
    }

    fun blockAfterNextUpdate(): BlockedUpdate {
        return BlockedUpdate().also { blocked ->
            synchronized(blockedAfterUpdates) {
                blockedAfterUpdates += blocked
            }
        }
    }

    fun failNextUpdate(error: Throwable = IllegalStateException("update failed")) {
        synchronized(failedUpdates) {
            failedUpdates += error
        }
    }

    fun updateAt(index: Int): Conversation = synchronized(updates) {
        updates[index]
    }

    fun updatesSnapshot(): List<Conversation> = synchronized(updates) {
        updates.toList()
    }

    suspend fun awaitUpdateCount(count: Int) {
        withTimeout(500) {
            while (synchronized(updates) { updates.size } < count) {
                delay(10)
            }
        }
    }

    suspend fun awaitTextUpdate(text: String) {
        withTimeout(500) {
            while (!conversation.value.hasTextPart(text)) {
                delay(10)
            }
        }
    }

    private fun Conversation.hasTextPart(text: String): Boolean =
        currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .any { it.text == text }
}

class BlockedUpdate {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
}

class FakeVoiceSessionApi : VoiceSessionApi {
    val createdSessions = mutableListOf<String>()
    private val blockedSessions = mutableListOf<BlockedSession>()
    private val sessionFailures = ArrayDeque<Throwable>()

    override suspend fun createSession(modelId: String): MobileVoiceSessionResponse {
        createdSessions += modelId
        val failure = synchronized(sessionFailures) {
            sessionFailures.removeFirstOrNull()
        }
        if (failure != null) {
            throw failure
        }
        val blocked = synchronized(blockedSessions) {
            blockedSessions.removeFirstOrNull()
        }
        blocked?.let {
            it.started.complete(Unit)
            it.release.await()
        }
        return MobileVoiceSessionResponse(
            token = "token-1",
            modelId = modelId,
            providerModel = "gemini-live-test",
            apiVersion = "v1alpha",
            websocketUrl = "wss://voice.test/live",
            inputSampleRate = 16000,
            outputSampleRate = 24000,
            liveConnectConfig = buildJsonObject {},
        )
    }

    fun blockNextSession(): BlockedSession {
        return BlockedSession().also { blocked ->
            synchronized(blockedSessions) {
                blockedSessions += blocked
            }
        }
    }

    fun failNextSession(error: Throwable) {
        synchronized(sessionFailures) {
            sessionFailures += error
        }
    }
}

class BlockedSession {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

class FakeVoiceAgentContextProvider(
    private val context: VoiceContext,
) : VoiceAgentContextProvider {
    override fun build(conversation: Conversation): VoiceContext = context
}
