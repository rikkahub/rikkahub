package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.PlaybackEpoch
import me.rerere.rikkahub.voiceagent.audio.VoicePlaybackEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class FakeVoiceAudioEngine : VoiceAudioEngine {
    val playedPcm16 = CopyOnWriteArrayList<String>()
    var suppressPlaybackCalls = 0
    var releaseCalls = 0
    var startCaptureCalls = 0
    var stopCaptureCalls = 0
    var startCaptureError: Throwable? = null
    var stopCaptureError: Throwable? = null
    var suppressPlaybackError: Throwable? = null
    var onStopCapture: (() -> Unit)? = null
    var onSuppressPlayback: (() -> Unit)? = null
    private var errorHandler: ((String) -> Unit)? = null
    var playbackSessionId: Long? = null
        private set
    private var playbackEventHandler: ((VoicePlaybackEvent) -> Unit)? = null
    private var nextPlaybackEpoch = PlaybackEpoch(0L)
    private var acceptingPlaybackEpoch: PlaybackEpoch? = null
    private val pendingDrainEpochs = ArrayDeque<PlaybackEpoch>()
    var markPlaybackTurnCompleteCalls = 0
        private set
    private var captureCallback: ((ByteArray) -> Unit)? = null
    private var debugInjectionCompleteCallback: (() -> Unit)? = null
    private val suspendedStartCaptures = mutableListOf<SuspendedCaptureStart>()
    private val blockedStartCaptures = mutableListOf<BlockedPlayback>()
    private val blockedStopCaptures = mutableListOf<BlockedPlayback>()
    private val blockedPlaybacks = mutableListOf<BlockedPlayback>()
    private val blockedAfterAcceptedPlaybacks = mutableListOf<BlockedPlayback>()
    private val blockedSuppressions = mutableListOf<BlockedPlayback>()
    private val blockedTurnCompletions = mutableListOf<BlockedPlayback>()
    private val blockedAfterReleaseDrains = mutableListOf<BlockedPlayback>()

    override fun setErrorHandler(onError: ((String) -> Unit)?) {
        errorHandler = onError
    }

    override fun setPlaybackEventHandler(onEvent: ((VoicePlaybackEvent) -> Unit)?) {
        playbackEventHandler = onEvent
    }

    override suspend fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) {
        startCaptureCalls += 1
        startCaptureError?.let { throw it }
        val suspended = synchronized(suspendedStartCaptures) {
            suspendedStartCaptures.removeFirstOrNull()
        }
        if (suspended != null) {
            suspended.entered.complete(Unit)
            try {
                suspended.release.await()
            } catch (cancellation: CancellationException) {
                suspended.cancelled.complete(Unit)
                if (suspended.ignoreCancellation) {
                    withContext(NonCancellable) { suspended.release.await() }
                } else {
                    throw cancellation
                }
            }
        }
        val blocked = synchronized(blockedStartCaptures) { blockedStartCaptures.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        captureCallback = onPcm16
        debugInjectionCompleteCallback = onDebugInjectionComplete
        suspended?.installed?.complete(Unit)
    }

    override fun stopCapture() {
        val blocked = synchronized(blockedStopCaptures) { blockedStopCaptures.removeFirstOrNull() }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        stopCaptureCalls += 1
        onStopCapture?.invoke()
        captureCallback = null
        debugInjectionCompleteCallback = null
        stopCaptureError?.let { throw it }
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
            val playbackEpoch = PlaybackEpoch(nextPlaybackEpoch.value + 1L)
            nextPlaybackEpoch = playbackEpoch
            acceptingPlaybackEpoch = playbackEpoch
            playbackEventHandler?.invoke(VoicePlaybackEvent.Active(playbackEpoch))
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
        onSuppressPlayback?.invoke()
        retirePlaybackEpochs().forEach { playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(it)) }
        suppressPlaybackError?.let { throw it }
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
        val blocked = synchronized(blockedAfterReleaseDrains) {
            blockedAfterReleaseDrains.removeFirstOrNull()
        }
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(500, TimeUnit.MILLISECONDS)
        }
        playbackEventHandler = null
    }

    fun completePlaybackDrain() {
        val epoch = pendingDrainEpochs.removeFirstOrNull() ?: return
        playbackEventHandler?.invoke(VoicePlaybackEvent.Drained(epoch))
    }

    private fun retirePlaybackEpochs(): List<PlaybackEpoch> = buildList {
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

    fun suspendNextStartCapture(): SuspendedCaptureStart {
        return SuspendedCaptureStart().also { suspended ->
            synchronized(suspendedStartCaptures) {
                suspendedStartCaptures += suspended
            }
        }
    }

    fun suspendNextUncancellableStartCapture(): SuspendedCaptureStart {
        return SuspendedCaptureStart(ignoreCancellation = true).also { suspended ->
            synchronized(suspendedStartCaptures) {
                suspendedStartCaptures += suspended
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

    fun blockAfterNextReleaseDrain(): BlockedPlayback {
        return BlockedPlayback().also { blocked ->
            synchronized(blockedAfterReleaseDrains) {
                blockedAfterReleaseDrains += blocked
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

internal class BlockedPlayback {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    fun awaitRelease() {
        started.countDown()
        check(release.await(500, TimeUnit.MILLISECONDS)) { "Timed out waiting to release blocked voice effect" }
    }
}

internal class SuspendedCaptureStart(
    val ignoreCancellation: Boolean = false,
) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val installed = CompletableDeferred<Unit>()
}
