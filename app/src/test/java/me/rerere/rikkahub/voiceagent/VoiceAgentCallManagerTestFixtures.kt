package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

internal class FakeManagedVoiceCallSession(
    initialState: VoiceAgentUiState = VoiceAgentUiState(),
    private val startFailure: Throwable? = null,
    private val endFailure: Throwable? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(initialState)
    var startCalls = 0
    var reconnectCalls = 0
    var endCalls = 0
    var endAndDrainCalls = 0
    var closeNowCalls = 0
    val diagnostics = mutableListOf<Pair<String, String>>()

    override fun start() { startCalls += 1; startFailure?.let { throw it } }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() { reconnectCalls += 1 }
    override fun recordDiagnostic(name: String, detail: String) { diagnostics += name to detail }
    override fun end() { endCalls += 1; endFailure?.let { throw it } }
    override suspend fun endAndDrain() { endAndDrainCalls += 1 }
    override fun closeNow() { closeNowCalls += 1 }
}

internal class BlockingCloseManagedVoiceCallSession(
    private val releaseClose: CountDownLatch,
    private val closeFailure: Throwable? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    val closeEntered = CountDownLatch(1)
    private val activeCleanupCalls = AtomicInteger()
    private val closeCallCount = AtomicInteger()
    private val endCallCount = AtomicInteger()
    private val overlapCallCount = AtomicInteger()
    val closeNowCalls: Int get() = closeCallCount.get()
    val endCalls: Int get() = endCallCount.get()
    val lifecycleOverlapCalls: Int get() = overlapCallCount.get()

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() {
        endCallCount.incrementAndGet()
        enterCleanup()
        activeCleanupCalls.decrementAndGet()
    }
    override suspend fun endAndDrain() = Unit
    override fun closeNow() {
        closeCallCount.incrementAndGet()
        enterCleanup()
        closeEntered.countDown()
        try {
            check(releaseClose.await(5, TimeUnit.SECONDS)) {
                "timed out waiting to release session close"
            }
        } finally {
            activeCleanupCalls.decrementAndGet()
        }
        closeFailure?.let { throw it }
    }

    private fun enterCleanup() {
        if (activeCleanupCalls.incrementAndGet() > 1) {
            overlapCallCount.incrementAndGet()
        }
    }
}

internal class BlockingEndManagedVoiceCallSession(
    private val releaseEnd: CountDownLatch,
    private val endFailure: Throwable? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    val endEntered = CountDownLatch(1)

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() {
        endEntered.countDown()
        check(releaseEnd.await(1, TimeUnit.SECONDS)) { "timed out waiting to release session end" }
        endFailure?.let { throw it }
    }
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
}

internal class BlockingStartManagedVoiceCallSession(
    private val releaseStart: CountDownLatch,
    private val closeFailure: Throwable? = null,
    private val releaseClose: CountDownLatch? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    val startEntered = CountDownLatch(1)
    val closeEntered = CountDownLatch(1)
    var closeNowCalls = 0

    override fun start() {
        startEntered.countDown()
        check(releaseStart.await(1, TimeUnit.SECONDS)) { "timed out waiting to release session start" }
    }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() {
        closeNowCalls += 1
        releaseClose?.let { release ->
            closeEntered.countDown()
            check(release.await(1, TimeUnit.SECONDS)) { "timed out waiting to release session close" }
        }
        closeFailure?.let { throw it }
    }
}

internal class CloseFailingManagedVoiceCallSession(
    private val closeFailure: Throwable,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var closeNowCalls = 0

    override fun start() { startCalls += 1 }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() {
        closeNowCalls += 1
        throw closeFailure
    }
}

internal class BlockingFirstCreateVoiceAgentCallFactory(
    private val releaseFirstCreate: CountDownLatch,
    private val firstSession: ManagedVoiceCallSession,
    private val secondSession: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val firstCreateEntered = CountDownLatch(1)
    private val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        val callIndex = createdCalls.getAndIncrement()
        val session = when (callIndex) {
            0 -> {
                firstCreateEntered.countDown()
                check(releaseFirstCreate.await(1, TimeUnit.SECONDS)) {
                    "timed out waiting to release first call factory invocation"
                }
                firstSession
            }
            1 -> secondSession
            else -> error("unexpected factory invocation $callIndex")
        }
        return RouteOwnedVoiceCallSession(session, routeLease)
    }
}

internal class BlockingFirstVoiceAgentCallFactory(
    private val releaseFactory: CountDownLatch,
) : VoiceAgentCallFactory {
    val factoryEntered = CountDownLatch(1)
    val createdCalls = AtomicInteger()
    val session = FakeManagedVoiceCallSession()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        createdCalls.incrementAndGet()
        factoryEntered.countDown()
        check(releaseFactory.await(1, TimeUnit.SECONDS)) { "timed out waiting to release call factory" }
        return RouteOwnedVoiceCallSession(session, routeLease)
    }
}

internal class BlockingFirstThenFailingVoiceAgentCallFactory(
    private val releaseFirstFactory: CountDownLatch,
    private val failure: Throwable,
) : VoiceAgentCallFactory {
    val firstFactoryEntered = CountDownLatch(1)
    val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        if (createdCalls.incrementAndGet() == 1) {
            firstFactoryEntered.countDown()
            check(releaseFirstFactory.await(1, TimeUnit.SECONDS)) {
                "timed out waiting to release first failing factory invocation"
            }
        }
        routeLease.retire()
        throw failure
    }
}

internal class BlockingFirstFailingVoiceAgentCallFactory(
    private val releaseFirstFailure: CountDownLatch,
    private val firstFailure: Throwable,
    private val releaseSecondCreate: CountDownLatch? = null,
    private val subsequentSessions: Array<out ManagedVoiceCallSession>,
) : VoiceAgentCallFactory {
    val firstCreateEntered = CountDownLatch(1)
    val secondCreateEntered = CountDownLatch(1)
    private val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        return when (val index = createdCalls.getAndIncrement()) {
            0 -> {
                firstCreateEntered.countDown()
                check(releaseFirstFailure.await(1, TimeUnit.SECONDS)) {
                    "timed out waiting to release first factory failure"
                }
                routeLease.retire()
                throw firstFailure
            }
            else -> {
                if (index == 1 && releaseSecondCreate != null) {
                    secondCreateEntered.countDown()
                    check(releaseSecondCreate.await(1, TimeUnit.SECONDS)) {
                        "timed out waiting to release second factory invocation"
                    }
                }
                RouteOwnedVoiceCallSession(subsequentSessions[index - 1], routeLease)
            }
        }
    }
}

internal class SignalingVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val createCalls = AtomicInteger()
    val secondCreateEntered = CountDownLatch(1)

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        val index = createCalls.getAndIncrement()
        if (index == 1) secondCreateEntered.countDown()
        return RouteOwnedVoiceCallSession(sessions[index], routeLease)
    }
}

internal class BlockedRetryDispatcher {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val releaseLatch = CountDownLatch(1)
    private val blockerEntered = CountDownLatch(1)

    init {
        dispatcher.executor.execute {
            blockerEntered.countDown()
            check(releaseLatch.await(5, TimeUnit.SECONDS)) { "timed out waiting to release retry dispatcher" }
        }
        check(blockerEntered.await(1, TimeUnit.SECONDS)) { "retry dispatcher blocker did not start" }
    }

    fun release() = releaseLatch.countDown()

    fun close() {
        release()
        dispatcher.close()
    }
}

internal class BlockingCollectorDispatcher : CoroutineDispatcher(), AutoCloseable {
    private val delegate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val releaseDispatch = CountDownLatch(1)
    val dispatchEntered = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchEntered.countDown()
        check(releaseDispatch.await(5, TimeUnit.SECONDS)) {
            "timed out waiting to release collector dispatch"
        }
        delegate.dispatch(context, block)
    }

    fun release() = releaseDispatch.countDown()

    override fun close() {
        release()
        delegate.close()
    }
}

internal class FakeVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val created = mutableListOf<CreatedCall>()
    private var nextSession = 0

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        created += CreatedCall(conversationId, config, routeLease.metadata.owner)
        return RouteOwnedVoiceCallSession(sessions[nextSession++], routeLease)
    }
}

internal class ConsumingFailingVoiceAgentCallFactory(
    private val failure: Throwable,
) : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        routeLease.retire()
        throw failure
    }
}

internal data class CreatedCall(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val routeOwner: VoiceAudioRouteOwner,
)

internal fun fakeManagerLaunchConfig(voiceModelId: String = "gemini-flash") = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = voiceModelId,
    assistantName = "Hermes",
    assistantPrompt = "system",
)

internal class CanonicalCancellationException(
    @Suppress("unused") private val identityMarker: Any,
) : CancellationException("cancel matching waiter")

internal class NonCopyableCleanupException(
    @Suppress("unused") private val identityMarker: Any,
    message: String,
) : IllegalStateException(message)

internal class CountingTelecomLease(
    disconnectFailure: Throwable? = null,
    disconnectEntered: CountDownLatch? = null,
    releaseRetirement: CountDownLatch? = null,
) {
    private val registry = VoiceAgentTelecomCallRegistry()
    private val call = CountingTelecomCall(
        disconnectFailure = disconnectFailure,
        disconnectEntered = disconnectEntered,
        releaseRetirement = releaseRetirement,
    )
    private val attempt = registry.beginAttempt()
    val lease: VoiceAgentRouteLease
    val retireCalls: Int get() = call.disconnectCalls

    init {
        check(registry.activate(attempt, call))
        registry.acknowledgeOutcome(attempt)
        lease = TelecomVoiceAgentRouteLease(attempt, registry)
    }
}

private class CountingTelecomCall(
    private val disconnectFailure: Throwable?,
    private val disconnectEntered: CountDownLatch?,
    private val releaseRetirement: CountDownLatch?,
) : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        disconnectEntered?.countDown()
        releaseRetirement?.let { release ->
            check(release.await(1, TimeUnit.SECONDS)) {
                "timed out waiting to release telecom retirement"
            }
        }
        disconnectFailure?.let { throw it }
    }
}
