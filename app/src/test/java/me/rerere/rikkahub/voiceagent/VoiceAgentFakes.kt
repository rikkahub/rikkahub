package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobSubmitResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileVoiceSessionResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class FakeGeminiLiveVoiceClient : GeminiLiveVoiceClient {
    val audioMessages = mutableListOf<String>()
    val audioStreamEndSessionIds = mutableListOf<Long?>()
    val toolResponses = mutableListOf<Pair<String, String>>()
    val textTurns = mutableListOf<Pair<Long?, String>>()
    val failToolResponses = mutableSetOf<String>()
    var failTextTurns = false
    var closeCalls = 0
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

    override fun sendAudio(base64Pcm16: String) {
        audioMessages += base64Pcm16
    }

    override fun sendAudio(base64Pcm16: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            audioMessages += base64Pcm16
            return true
        }
    }

    override fun activateOutboundSession(sessionId: Long) {
        outboundSessionId = sessionId
        activateOutboundSessionEvent?.let { event ->
            eventHandlers.lastOrNull()?.invoke(event)
        }
    }

    override fun sendAudioStreamEnd(sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            audioStreamEndSessionIds += sessionId
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

    override fun sendToolResponse(callId: String, answer: String): Boolean {
        return sendToolResponse(callId = callId, answer = answer, sessionId = null)
    }

    override fun sendToolResponse(callId: String, answer: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            val blocked = synchronized(blockedResponses) {
                blockedResponses[callId]?.removeFirstOrNull()
            }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(blocked.timeoutMillis, TimeUnit.MILLISECONDS)
            }
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            onBeforeToolResponseRecorded?.invoke()
            if (callId in failToolResponses) {
                return false
            }
            toolResponses += callId to answer
            return true
        }
    }

    override fun sendTextTurn(text: String): Boolean {
        return sendTextTurn(text = text, sessionId = null)
    }

    override fun sendTextTurn(text: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            if (sessionId != null && outboundSessionId != sessionId) {
                return false
            }
            if (failTextTurns) {
                return false
            }
            textTurns += sessionId to text
            return true
        }
    }

    override fun close() {
        onClose?.invoke()
        outboundSessionId = null
        closeCalls += 1
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
    val requests = mutableListOf<Pair<String, String>>()
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
        return MobileHermesJobSubmitResponse(
            jobId = job.jobId,
            callId = callId,
            status = "queued",
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
        return MobileHermesJobPollResponse(
            jobId = job.jobId,
            callId = job.callId,
            status = "failed",
            error = "Hermes job canceled",
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
            MobileHermesJobPollResponse(
                jobId = job.jobId,
                callId = response.callId,
                status = "succeeded",
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
            MobileHermesJobPollResponse(
                jobId = job.jobId,
                callId = callId,
                status = "failed",
                error = message,
                createdAt = "2026-06-11T00:00:00.000Z",
                completedAt = "2026-06-11T00:00:01.000Z",
            )
        )
    }

    fun expireJob(callId: String, message: String? = null) {
        val job = call(callId)
        job.result.complete(
            MobileHermesJobPollResponse(
                jobId = job.jobId,
                callId = callId,
                status = "expired",
                error = message,
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
            scriptedPolls.getOrPut(jobId) { ArrayDeque() } += MobileHermesJobPollResponse(
                jobId = jobId,
                callId = callId,
                status = "succeeded",
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
            submitResponses[callId] = MobileHermesJobSubmitResponse(
                jobId = "",
                callId = callId,
                status = status,
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
                response = MobileHermesJobPollResponse(
                    jobId = job.jobId,
                    callId = callId,
                    status = "queued",
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
    val playedLocalCuePcm16 = CopyOnWriteArrayList<String>()
    var failLocalCuePlayback = false
    private val localCuePlaybackAttemptCount = AtomicInteger()
    val localCuePlaybackAttempts: Int
        get() = localCuePlaybackAttemptCount.get()
    var suppressPlaybackCalls = 0
    var releaseCalls = 0
    var startCaptureCalls = 0
    var stopCaptureCalls = 0
    var startCaptureError: Throwable? = null
    private var errorHandler: ((String) -> Unit)? = null
    private var playbackSessionId: Long? = null
    private var captureCallback: ((ByteArray) -> Unit)? = null
    private var debugInjectionCompleteCallback: (() -> Unit)? = null
    private val blockedStartCaptures = mutableListOf<BlockedPlayback>()
    private val blockedStopCaptures = mutableListOf<BlockedPlayback>()
    private val blockedPlaybacks = mutableListOf<BlockedPlayback>()
    private val blockedSuppressions = mutableListOf<BlockedPlayback>()

    override fun setErrorHandler(onError: ((String) -> Unit)?) {
        errorHandler = onError
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

    override fun playPcm16(base64Pcm16: String) {
        playPcm16(base64Pcm16 = base64Pcm16, sessionId = null)
    }

    override fun playPcm16(base64Pcm16: String, sessionId: Long?) {
        if (sessionId != null && playbackSessionId != sessionId) {
            return
        }
        val blocked = synchronized(blockedPlaybacks) { blockedPlaybacks.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        if (sessionId != null && playbackSessionId != sessionId) {
            return
        }
        playedPcm16 += base64Pcm16
    }

    override fun playLocalCuePcm16(base64Pcm16: String, sessionId: Long?): Boolean {
        localCuePlaybackAttemptCount.incrementAndGet()
        if (failLocalCuePlayback) {
            return false
        }
        if (sessionId != null && playbackSessionId != sessionId) {
            return false
        }
        playedLocalCuePcm16 += base64Pcm16
        return true
    }

    override fun activatePlaybackSession(sessionId: Long) {
        playbackSessionId = sessionId
    }

    override fun invalidatePlaybackSession() {
        playbackSessionId = null
    }

    override fun suppressPlayback() {
        val blocked = synchronized(blockedSuppressions) { blockedSuppressions.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        suppressPlaybackCalls += 1
    }

    override fun release() {
        releaseCalls += 1
    }

    fun blockNextPlayback(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedPlaybacks) {
                blockedPlaybacks += blocked
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
    override val conversation: StateFlow<Conversation> = MutableStateFlow(initialConversation)

    override suspend fun update(transform: (Conversation) -> Conversation) {
        val blockedBeforeUpdate = synchronized(blockedUpdates) { blockedUpdates.removeFirstOrNull() }
        if (blockedBeforeUpdate != null) {
            blockedBeforeUpdate.started.countDown()
            blockedBeforeUpdate.release.await(500, TimeUnit.MILLISECONDS)
        }
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
