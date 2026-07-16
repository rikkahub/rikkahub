package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentCallServiceLifecycleTest {
    @Test
    fun `startup terminal predicate covers state and generation`() {
        val currentGeneration = 7L
        val cases = listOf(
            VoiceSessionStatus.PreparingContext to false,
            VoiceSessionStatus.Connected to true,
            VoiceSessionStatus.Error("failed") to true,
            VoiceSessionStatus.Ended to true,
        )

        cases.forEach { (session, expected) ->
            assertEquals(
                session.toString(),
                expected,
                isVoiceAgentStartupTerminal(
                    startGeneration = currentGeneration,
                    currentGeneration = currentGeneration,
                    session = session,
                ),
            )
        }
        assertTrue(
            isVoiceAgentStartupTerminal(
                startGeneration = currentGeneration - 1,
                currentGeneration = currentGeneration,
                session = VoiceSessionStatus.PreparingContext,
            ),
        )
    }

    @Test
    fun `end detaches exact installed session rejects repeat and old completion cannot stop newer generation`() = runTest {
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val oldSession = LifecycleManagedSession(
            onEndAndDrain = {
                drainStarted.complete(Unit)
                releaseDrain.await()
            },
        )
        val manager = VoiceAgentCallManager(LifecycleCallFactory(oldSession, LifecycleManagedSession()))
        val oldRoute = LifecycleTelecomRoute()
        val oldConversation = Uuid.random()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, scope, host, endDrainTimeoutMillis = 1_000)
        try {
            manager.start(oldConversation, lifecycleLaunchConfig(), oldRoute.lease, scope)

            assertTrue(lifecycle.endCall())
            withTimeout(TEST_TIMEOUT_MS) { drainStarted.await() }
            assertNull(manager.activeConversationId.value)
            assertEquals(1, oldRoute.retireCalls)
            assertEquals(1, oldSession.endAndDrainCalls)
            assertFalse(lifecycle.endCall())

            lifecycle.beginStart()
            val newerConversation = Uuid.random()
            manager.start(
                newerConversation,
                lifecycleLaunchConfig(voiceModelId = "new-model"),
                DirectFallbackVoiceAgentRouteLease(
                    VoiceAgentTelecomFailure("newer", "newer"),
                ),
                scope,
            )
            releaseDrain.complete(Unit)
            runCurrent()

            assertEquals(newerConversation, manager.activeConversationId.value)
            assertEquals(0, host.stopForegroundCalls)
            assertEquals(0, host.stopSelfCalls)
            assertEquals(0, host.endCompletedCalls)
        } finally {
            releaseDrain.complete(Unit)
            manager.closeNow()
            scope.cancel()
        }
    }

    @Test
    fun `destruction cannot abandon an entered detached drain`() = runTest {
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val drainFinished = CompletableDeferred<Unit>()
        val session = LifecycleManagedSession(
            onEndAndDrain = {
                drainStarted.complete(Unit)
                releaseDrain.await()
                drainFinished.complete(Unit)
            },
        )
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, scope, host, endDrainTimeoutMillis = 1_000)
        try {
            manager.start(Uuid.random(), lifecycleLaunchConfig(), LifecycleTelecomRoute().lease, scope)
            assertTrue(lifecycle.endCall())
            withTimeout(TEST_TIMEOUT_MS) { drainStarted.await() }

            lifecycle.destroy()
            releaseDrain.complete(Unit)

            withTimeout(TEST_TIMEOUT_MS) { drainFinished.await() }
            assertTrue(host.destroyBaseCalls == 1)
            assertEquals(0, host.stopForegroundCalls)
            assertEquals(0, host.stopSelfCalls)
        } finally {
            releaseDrain.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `timed out drain retires route closes immediately and cannot stop newer generation`() = runTest {
        val neverCompletes = CompletableDeferred<Unit>()
        val closeFailure = IllegalStateException("immediate close failed")
        val session = LifecycleManagedSession(
            closeNowFailure = closeFailure,
            onEndAndDrain = { neverCompletes.await() },
        )
        val route = LifecycleTelecomRoute()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, scope, host, endDrainTimeoutMillis = 100)
        try {
            manager.start(Uuid.random(), lifecycleLaunchConfig(), route.lease, scope)
            assertTrue(lifecycle.endCall())
            lifecycle.beginStart()

            advanceTimeBy(100)
            runCurrent()

            assertEquals(1, route.retireCalls)
            assertEquals(1, session.closeNowCalls)
            assertEquals(1, host.reportedFailures.size)
            val reported = host.reportedFailures.single()
            assertTrue(reported is VoiceAgentEndDrainTimeoutException)
            assertEquals(listOf(closeFailure.message), reported.suppressed.map { it.message })
            assertEquals(0, host.endCompletedCalls)
            assertEquals(0, host.stopForegroundCalls)
            assertEquals(0, host.stopSelfCalls)
        } finally {
            neverCompletes.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `deadline after route failure closes delegate once and preserves replacement ownership and failure order`() =
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val routeFailure = IllegalStateException("route retirement failed")
            val closeFailure = IllegalArgumentException("delegate close failed")
            val session = LifecycleManagedSession(
                closeNowFailure = closeFailure,
                onEndAndDrain = { neverCompletes.await() },
            )
            val route = LifecycleTelecomRoute(retireFailure = routeFailure)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
            val host = RecordingLifecycleHost()
            val lifecycle = VoiceAgentCallServiceLifecycle(manager, scope, host, endDrainTimeoutMillis = 100)
            var replacement: LifecycleTelecomReplacement? = null
            try {
                manager.start(Uuid.random(), lifecycleLaunchConfig(), route.lease, scope)
                assertTrue(lifecycle.endCall())
                replacement = route.activateReplacement()
                lifecycle.beginStart()

                advanceTimeBy(100)
                runCurrent()

                assertEquals(1, route.retireCalls)
                assertEquals(1, session.closeNowCalls)
                assertEquals(0, replacement.call.disconnectCalls)
                val reported = host.reportedFailures.single()
                assertEquals(routeFailure::class, reported::class)
                assertEquals(routeFailure.message, reported.message)
                assertEquals(2, reported.suppressed.size)
                assertTrue(reported.suppressed[0] is TimeoutCancellationException)
                assertEquals(closeFailure::class, reported.suppressed[1]::class)
                assertEquals(closeFailure.message, reported.suppressed[1].message)
            } finally {
                neverCompletes.complete(Unit)
                replacement?.retire()
                scope.cancel()
            }
        }

    @Test
    fun `throwing drain after destruction is reported without escaping or skipping destroy`() = runTest {
        val releaseDrain = CompletableDeferred<Unit>()
        val drainFailure = IllegalStateException("drain failed")
        val session = LifecycleManagedSession(
            onEndAndDrain = {
                releaseDrain.await()
                throw drainFailure
            },
        )
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, scope, host, endDrainTimeoutMillis = 1_000)
        try {
            manager.start(Uuid.random(), lifecycleLaunchConfig(), LifecycleTelecomRoute().lease, scope)
            assertTrue(lifecycle.endCall())
            lifecycle.destroy()
            releaseDrain.complete(Unit)
            runCurrent()

            assertEquals(1, host.reportedFailures.size)
            assertEquals(drainFailure.message, host.reportedFailures.single().message)
            assertEquals(1, host.destroyBaseCalls)
            assertEquals(0, host.stopForegroundCalls)
            assertEquals(0, host.stopSelfCalls)
        } finally {
            releaseDrain.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `end failure stays primary all conditional stages run and aggregate is safely reported`() = runTest {
        val drainFailure = IllegalStateException("drain")
        val completedFailure = IllegalArgumentException("completed")
        val foregroundFailure = UnsupportedOperationException("foreground")
        val selfFailure = IllegalStateException("self")
        val session = LifecycleManagedSession(onEndAndDrain = { throw drainFailure })
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val host = RecordingLifecycleHost(
            endCompletedFailure = completedFailure,
            stopForegroundFailure = foregroundFailure,
            stopSelfFailure = selfFailure,
        )
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, scope, host, endDrainTimeoutMillis = 1_000)
        try {
            manager.start(Uuid.random(), lifecycleLaunchConfig(), LifecycleTelecomRoute().lease, scope)

            assertTrue(lifecycle.endCall())
            runCurrent()

            val reported = host.reportedFailures.single()
            assertEquals(drainFailure::class, reported::class)
            assertEquals(drainFailure.message, reported.message)
            assertEquals(
                listOf(completedFailure.message, foregroundFailure.message, selfFailure.message),
                reported.suppressed.map { it.message },
            )
            assertEquals(
                listOf("cancelNotification", "startForeground", "endCompleted", "stopForeground", "stopSelf", "reportFailure"),
                host.events,
            )
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}

internal class RecordingLifecycleHost(
    val events: MutableList<String> = mutableListOf(),
    private val endCompletedFailure: Throwable? = null,
    private val stopForegroundFailure: Throwable? = null,
    private val stopSelfFailure: Throwable? = null,
) : VoiceAgentCallServiceLifecycleHost {
    val reportedFailures = mutableListOf<Throwable>()
    val foregroundStates = mutableListOf<VoiceAgentUiState>()
    var stopForegroundCalls = 0
    var stopSelfCalls = 0
    var endCompletedCalls = 0
    var destroyBaseCalls = 0

    override fun cancelNotification() {
        events += "cancelNotification"
    }

    override fun startForeground(conversationId: String, state: VoiceAgentUiState) {
        events += "startForeground"
        foregroundStates += state
    }

    override fun endCompleted(conversationId: Uuid?) {
        events += "endCompleted"
        endCompletedCalls += 1
        endCompletedFailure?.let { throw it }
    }

    override fun stopForeground() {
        events += "stopForeground"
        stopForegroundCalls += 1
        stopForegroundFailure?.let { throw it }
    }

    override fun stopSelf() {
        events += "stopSelf"
        stopSelfCalls += 1
        stopSelfFailure?.let { throw it }
    }

    override fun reportCleanupFailure(error: Throwable) {
        events += "reportFailure"
        reportedFailures += error
    }

    override fun destroyBaseService() {
        events += "destroyBase"
        destroyBaseCalls += 1
    }
}

internal class LifecycleManagedSession(
    initialSessionStatus: VoiceSessionStatus = VoiceSessionStatus.PreparingContext,
    private val events: MutableList<String>? = null,
    private val closeNowFailure: Throwable? = null,
    private val onEndAndDrain: suspend () -> Unit = {},
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState(session = initialSessionStatus))
    var endAndDrainCalls = 0
    var closeNowCalls = 0

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) {
        events?.add("diagnostic:$name:$detail")
    }
    override fun end() = Unit
    override suspend fun endAndDrain() {
        endAndDrainCalls += 1
        onEndAndDrain()
    }
    override fun closeNow() {
        closeNowCalls += 1
        events?.add("close")
        closeNowFailure?.let { throw it }
    }
}

internal class LifecycleCallFactory(
    private vararg val sessions: LifecycleManagedSession,
) : VoiceAgentCallFactory {
    private var next = 0
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession = RouteOwnedVoiceCallSession(sessions[next++], routeLease)
}

internal class LifecycleTelecomRoute(
    retireFailure: Throwable? = null,
) {
    private val registry = VoiceAgentTelecomCallRegistry()
    private val call = LifecycleTelecomCall(retireFailure)
    val attempt = registry.beginAttempt()
    val lease: VoiceAgentRouteLease
    val retireCalls: Int get() = call.disconnectCalls

    init {
        check(registry.activate(attempt, call))
        registry.acknowledgeOutcome(attempt)
        lease = TelecomVoiceAgentRouteLease(attempt, registry)
    }

    fun activateReplacement(): LifecycleTelecomReplacement {
        val replacement = LifecycleTelecomCall()
        val replacementAttempt = registry.beginAttempt()
        check(registry.activate(replacementAttempt, replacement))
        registry.acknowledgeOutcome(replacementAttempt)
        return LifecycleTelecomReplacement(registry, replacementAttempt, replacement)
    }
}

internal class LifecycleTelecomReplacement(
    private val registry: VoiceAgentTelecomCallRegistry,
    private val attempt: VoiceAgentTelecomAttemptId,
    val call: LifecycleTelecomCall,
) {
    val disconnectCalls: Int get() = call.disconnectCalls

    fun retire() = registry.retireOwnedAttempt(attempt)
}

internal class LifecycleTelecomCall(
    private val disconnectFailure: Throwable? = null,
) : VoiceAgentTelecomCall {
    var disconnectCalls = 0
    override fun disconnectFromApp() {
        disconnectCalls += 1
        disconnectFailure?.let { throw it }
    }
}

internal fun lifecycleLaunchConfig(voiceModelId: String = "gemini-flash") = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = voiceModelId,
    assistantName = "Hermes",
    assistantPrompt = "system",
)
