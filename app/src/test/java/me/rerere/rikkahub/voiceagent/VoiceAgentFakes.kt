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
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileVoiceSessionResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class FakeGeminiLiveVoiceClient : GeminiLiveVoiceClient {
    val audioMessages = mutableListOf<String>()
    val audioStreamEndSessionIds = mutableListOf<Long?>()
    val toolResponses = mutableListOf<Pair<String, String>>()
    val failToolResponses = mutableSetOf<String>()
    var closeCalls = 0
    var onBeforeToolResponseRecorded: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var connectEvent: GeminiLiveEvent? = null
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

class FakeVoiceToolApi : VoiceToolApi {
    val requests = mutableListOf<Pair<String, String>>()
    private val calls = mutableMapOf<String, MutableList<PendingHermesCall>>()

    override suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse {
        val call = nextCallForRequest(callId)
        requests += callId to prompt
        call.request.complete(callId to prompt)
        return try {
            call.result.await()
        } catch (error: kotlinx.coroutines.CancellationException) {
            call.cancelled.complete(Unit)
            throw error
        }
    }

    suspend fun awaitRequest(callId: String? = null): Pair<String, String> {
        return withTimeout(500) {
            if (callId == null) {
                firstCall().request.await()
            } else {
                call(callId).request.await()
            }
        }
    }

    fun complete(response: MobileHermesResponse) {
        call(response.callId).result.complete(response)
    }

    fun fail(error: Throwable) {
        firstCall().result.completeExceptionally(error)
    }

    fun fail(callId: String, error: Throwable) {
        call(callId).result.completeExceptionally(error)
    }

    suspend fun awaitCancelled(callId: String) {
        withTimeout(500) {
            call(callId).cancelled.await()
        }
    }

    fun wasCancelled(callId: String): Boolean = call(callId).cancelled.isCompleted

    private fun nextCallForRequest(callId: String): PendingHermesCall = synchronized(calls) {
        val callList = calls.getOrPut(callId) { mutableListOf() }
        callList.firstOrNull { !it.request.isCompleted }
            ?: PendingHermesCall().also(callList::add)
    }

    private fun call(callId: String): PendingHermesCall = synchronized(calls) {
        val callList = calls.getOrPut(callId) { mutableListOf() }
        callList.firstOrNull { !it.result.isCompleted && !it.cancelled.isCompleted }
            ?: callList.lastOrNull()
            ?: PendingHermesCall().also(callList::add)
    }

    private fun firstCall(): PendingHermesCall = synchronized(calls) {
        calls.values.flatten().firstOrNull { !it.result.isCompleted && !it.cancelled.isCompleted }
            ?: PendingHermesCall().also { calls.getOrPut("") { mutableListOf() } += it }
    }
}

class PendingHermesCall {
    val request = CompletableDeferred<Pair<String, String>>()
    val result = CompletableDeferred<MobileHermesResponse>()
    val cancelled = CompletableDeferred<Unit>()
}

class FakeVoiceAudioEngine : VoiceAudioEngine {
    val playedPcm16 = mutableListOf<String>()
    var suppressPlaybackCalls = 0
    var releaseCalls = 0
    var startCaptureCalls = 0
    var stopCaptureCalls = 0
    var startCaptureError: Throwable? = null
    private var errorHandler: ((String) -> Unit)? = null
    private var playbackSessionId: Long? = null
    private var captureCallback: ((ByteArray) -> Unit)? = null
    private var debugInjectionCompleteCallback: (() -> Unit)? = null
    private val blockedPlaybacks = mutableListOf<BlockedPlayback>()
    private val blockedSuppressions = mutableListOf<BlockedPlayback>()

    override fun setErrorHandler(onError: ((String) -> Unit)?) {
        errorHandler = onError
    }

    override fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) {
        startCaptureCalls += 1
        startCaptureError?.let { throw it }
        captureCallback = onPcm16
        debugInjectionCompleteCallback = onDebugInjectionComplete
    }

    override fun stopCapture() {
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

class FakeVoiceConversationStore : VoiceConversationStore {
    private val updates = mutableListOf<Conversation>()
    private val blockedUpdates = mutableListOf<BlockedUpdate>()
    override val conversation: StateFlow<Conversation> = MutableStateFlow(
        Conversation.ofId(id = Uuid.random())
    )

    override suspend fun update(transform: (Conversation) -> Conversation) {
        val blocked = synchronized(blockedUpdates) { blockedUpdates.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        val flow = conversation as MutableStateFlow<Conversation>
        flow.value = transform(flow.value)
        synchronized(updates) {
            updates += flow.value
        }
    }

    fun blockNextUpdate(): BlockedUpdate {
        return BlockedUpdate().also { blocked ->
            synchronized(blockedUpdates) {
                blockedUpdates += blocked
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

    override suspend fun createSession(modelId: String): MobileVoiceSessionResponse {
        createdSessions += modelId
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
