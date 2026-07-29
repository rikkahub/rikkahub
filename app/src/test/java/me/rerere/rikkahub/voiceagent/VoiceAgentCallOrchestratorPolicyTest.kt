package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallOrchestratorPolicyTest {
    @Test
    fun `throwing fallback diagnostic still publishes degraded status and starts collector`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val failure = VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable")
        val route = DirectFallbackVoiceAgentRouteLease(failure)
        val session = OrchestratorFakeSession(
            routeMetadata = route.metadata,
            onDiagnostic = { _, _ -> throw IllegalStateException("diagnostic callback failed") },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("throwing-fallback-diagnostic")) }
        runCurrent()

        assertTrue(result.await() is VoiceAgentCallStartResult.Active)
        assertEquals(VoiceCallStatus.Degraded(failure.detail), orchestrator.state.value.call)
        assertEquals(1, session.collectorCount())
        appJob.cancel()
    }

    @Test
    fun `throwing session error diagnostic still publishes degraded session state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            onDiagnostic = { _, _ -> throw IllegalStateException("diagnostic callback failed") },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("throwing-error-diagnostic")) }
            .also { runCurrent() }
            .await()

        session.emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("connection lost")))
        runCurrent()

        assertEquals(VoiceSessionStatus.Error("connection lost"), orchestrator.state.value.session)
        assertEquals(VoiceCallStatus.Degraded("connection lost"), orchestrator.state.value.call)
        assertEquals(1, session.collectorCount())
        appJob.cancel()
    }

    @Test
    fun `throwing matching reconnect does not escape active start result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.Error("connection lost")),
            routeMetadata = route.lease.metadata,
            onReconnect = { throw IllegalStateException("reconnect callback failed") },
        )
        val request = orchestratorRequest("throwing-reconnect")
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(request) }.also { runCurrent() }.await()

        val matching = async { orchestrator.start(request) }
        runCurrent()

        assertTrue(matching.await() is VoiceAgentCallStartResult.Active)
        assertEquals(1, session.reconnectCalls)
        assertEquals(
            ActiveVoiceAgentIdentity(request.conversationId, request.transport),
            orchestrator.activeIdentity.value,
        )
        appJob.cancel()
    }

    @Test
    fun `initial Ended state is processed after publication and cleans immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            VoiceAgentCleanupResult.Completed
        }
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.Ended),
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("initial-ended")) }
        runCurrent()

        assertTrue(result.await() is VoiceAgentCallStartResult.Active)
        cleanupEntered.await()
        assertEquals(
            VoiceAgentCallLifecycle.Idle,
            orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
        )
        assertNull(orchestrator.activeIdentity.value)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        appJob.cancel()
    }

    @Test
    fun `initial Error with unusable route is processed and cleans immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            VoiceAgentCleanupResult.Completed
        }
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.Error("initial route failure")),
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
        ).apply { isRouteUsable = false }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("initial-unusable-error")) }
        runCurrent()

        assertTrue(result.await() is VoiceAgentCallStartResult.Active)
        cleanupEntered.await()
        assertEquals(
            VoiceAgentCallLifecycle.Idle,
            orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
        )
        assertNull(orchestrator.activeIdentity.value)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        appJob.cancel()
    }

    @Test
    fun `initial usable Error stays active degraded and matching start reconnects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.Error("initial connection failure")),
            routeMetadata = route.lease.metadata,
        )
        val request = orchestratorRequest("initial-usable-error")
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val first = async { orchestrator.start(request) }
        runCurrent()

        assertTrue(first.await() is VoiceAgentCallStartResult.Active)
        assertEquals(
            ActiveVoiceAgentIdentity(request.conversationId, request.transport),
            orchestrator.activeIdentity.value,
        )
        assertEquals(VoiceCallStatus.Degraded("initial connection failure"), orchestrator.state.value.call)
        assertEquals(
            listOf("voice_call_start_failed" to "initial connection failure"),
            session.diagnostics,
        )
        assertTrue(
            async { orchestrator.start(request) }.also { runCurrent() }.await() is VoiceAgentCallStartResult.Active,
        )
        assertEquals(1, session.reconnectCalls)
        appJob.cancel()
    }

    @Test
    fun `usable session Error stays active degraded and matching start reconnects once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata)
        val request = orchestratorRequest("usable-error")
        var routeCalls = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = {
                routeCalls += 1
                route.lease
            },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(request) }.also { runCurrent() }.await()

        session.emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("connection lost")))
        runCurrent()

        assertEquals(
            ActiveVoiceAgentIdentity(request.conversationId, request.transport),
            orchestrator.activeIdentity.value,
        )
        assertEquals(VoiceCallStatus.Degraded("connection lost"), orchestrator.state.value.call)
        assertEquals(listOf("voice_call_start_failed" to "connection lost"), session.diagnostics)
        assertTrue(
            async { orchestrator.start(request) }.also { runCurrent() }.await() is VoiceAgentCallStartResult.Active,
        )
        assertEquals(1, session.reconnectCalls)
        assertEquals(1, routeCalls)

        session.emit(VoiceAgentUiState(session = VoiceSessionStatus.Connected))
        runCurrent()
        assertEquals(VoiceCallStatus.Degraded("connection lost"), orchestrator.state.value.call)
        appJob.cancel()
    }

    @Test
    fun `recording an Error diagnostic does not recursively record the same Error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val diagnosticLine = VoiceDiagnosticLine(
            name = "voice_call_start_failed",
            detail = "connection lost",
            at = "test",
        )
        var stateMutationPublished = false
        lateinit var session: OrchestratorFakeSession
        session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            onDiagnostic = { _, _ ->
                if (!stateMutationPublished) {
                    stateMutationPublished = true
                    session.emit(session.state.value.copy(diagnostics = listOf(diagnosticLine)))
                }
            },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("error-diagnostic-feedback")) }
            .also { runCurrent() }
            .await()

        session.emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("connection lost")))
        runCurrent()

        assertEquals(
            listOf("voice_call_start_failed" to "connection lost"),
            session.diagnostics,
        )
        assertEquals(listOf(diagnosticLine), orchestrator.state.value.diagnostics)
        appJob.cancel()
    }

    @Test
    fun `unusable Error and Ended detach current calls with immediate cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        suspend fun exercise(terminal: VoiceSessionStatus, label: String) {
            val appJob = SupervisorJob()
            val cleanup = OrchestratorFakeCleanupOperation()
            val route = OrchestratorFakeRoute()
            val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata, cleanupOperation = cleanup)
            val orchestrator = VoiceAgentCallOrchestrator(
                factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
                resolveRoute = { route.lease },
                appScope = CoroutineScope(appJob + dispatcher),
            )
            async { orchestrator.start(orchestratorRequest(label)) }.also { runCurrent() }.await()
            if (terminal is VoiceSessionStatus.Error) session.isRouteUsable = false

            session.emit(VoiceAgentUiState(session = terminal))
            runCurrent()

            assertNull(orchestrator.activeIdentity.value)
            assertEquals(VoiceAgentUiState(), orchestrator.state.value)
            assertEquals(
                VoiceAgentCallLifecycle.Idle,
                orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
            )
            assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
            appJob.cancel()
        }

        exercise(VoiceSessionStatus.Error("route retired"), "unusable")
        exercise(VoiceSessionStatus.Ended, "ended")
    }

    @Test
    fun `matching start after unusable Error creates a fresh session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val first = OrchestratorFakeSession()
        val second = OrchestratorFakeSession()
        val sessions = ArrayDeque(listOf(first, second))
        val factory = OrchestratorFakeFactory { _, routeLease, _ ->
            VoiceAgentSessionCreationResult.Created(
                sessions.removeFirst().also { session ->
                    check(session.routeMetadata.owner == routeLease.metadata.owner)
                },
            )
        }
        val request = orchestratorRequest("retry-after-unusable-error")
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { OrchestratorFakeRoute().lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(request) }.also { runCurrent() }.await()
        first.isRouteUsable = false

        first.emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("route retired")))
        runCurrent()
        orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle }

        val retry = async { orchestrator.start(request) }
        runCurrent()

        assertTrue(retry.await() is VoiceAgentCallStartResult.Active)
        assertEquals(2, factory.calls)
        assertEquals(
            ActiveVoiceAgentIdentity(request.conversationId, request.transport),
            orchestrator.activeIdentity.value,
        )
        assertEquals(1, second.collectorCount())
        appJob.cancel()
    }

    @Test
    fun `route diagnostics degrade fallback while healthy Telecom becomes background capable`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fallbackJob = SupervisorJob()
        val failure = VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable")
        val fallbackLease = DirectFallbackVoiceAgentRouteLease(failure)
        val fallbackSession = OrchestratorFakeSession(routeMetadata = fallbackLease.metadata)
        val fallback = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(fallbackSession)
            },
            resolveRoute = { fallbackLease },
            appScope = CoroutineScope(fallbackJob + dispatcher),
        )

        async { fallback.start(orchestratorRequest("fallback")) }.also { runCurrent() }.await()

        assertEquals(VoiceCallStatus.Degraded(failure.detail), fallback.state.value.call)
        assertEquals(listOf(failure.diagnosticName to failure.detail), fallbackSession.diagnostics)

        val telecomJob = SupervisorJob()
        val telecomRoute = OrchestratorFakeRoute()
        val telecomSession = OrchestratorFakeSession(routeMetadata = telecomRoute.lease.metadata)
        val telecom = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(telecomSession)
            },
            resolveRoute = { telecomRoute.lease },
            appScope = CoroutineScope(telecomJob + dispatcher),
        )

        async { telecom.start(orchestratorRequest("telecom")) }.also { runCurrent() }.await()

        assertEquals(VoiceAudioRouteOwner.Telecom, telecomRoute.lease.metadata.owner)
        assertEquals(VoiceCallStatus.BackgroundCapable, telecom.state.value.call)
        assertTrue(telecomSession.diagnostics.isEmpty())

        telecomSession.emit(VoiceAgentUiState(session = VoiceSessionStatus.Reconnecting))
        runCurrent()
        assertEquals(VoiceCallStatus.BackgroundCapable, telecom.state.value.call)
        fallbackJob.cancel()
        telecomJob.cancel()
    }

    @Test
    fun `reentrant diagnostic close rejects the remaining stale Error projection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cleanup = OrchestratorFakeCleanupOperation()
        val route = OrchestratorFakeRoute()
        lateinit var orchestrator: VoiceAgentCallOrchestrator
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onDiagnostic = { _, _ -> orchestrator.closeNow() },
        )
        orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("stale")) }.also { runCurrent() }.await()

        session.emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("late error")))
        runCurrent()

        assertNull(orchestrator.activeIdentity.value)
        assertEquals(VoiceAgentUiState(), orchestrator.state.value)
        assertFalse(orchestrator.state.value.call is VoiceCallStatus.Degraded)
        assertEquals(
            VoiceAgentCallLifecycle.Idle,
            orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
        )
        appJob.cancel()
    }

    @Test
    fun `reentrant route diagnostic close rejects stale route call-status projection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val failure = VoiceAgentTelecomFailure("fallback", "Telecom route failed")
        val route = DirectFallbackVoiceAgentRouteLease(failure)
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            VoiceAgentCleanupResult.Completed
        }
        lateinit var orchestrator: VoiceAgentCallOrchestrator
        val session = OrchestratorFakeSession(
            routeMetadata = route.metadata,
            cleanupOperation = cleanup,
            onDiagnostic = { _, _ -> orchestrator.closeNow() },
        )
        orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("stale-route-status")) }
        runCurrent()

        assertTrue(result.await() is VoiceAgentCallStartResult.Active)
        assertNull(orchestrator.activeIdentity.value)
        assertEquals(VoiceAgentUiState(), orchestrator.state.value)
        assertFalse(orchestrator.state.value.call is VoiceCallStatus.Degraded)
        cleanupEntered.await()
        assertEquals(
            VoiceAgentCallLifecycle.Idle,
            orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
        )
        appJob.cancel()
    }

    @Test
    fun `blocked active command does not hold orchestrator state lock`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val commandEntered = CompletableDeferred<Unit>()
        val releaseCommand = CountDownLatch(1)
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            onInterrupt = {
                commandEntered.complete(Unit)
                releaseCommand.await()
            },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("blocked-command")) }.also { runCurrent() }.await()

        val interrupt = async(Dispatchers.Default) { orchestrator.interrupt() }
        commandEntered.await()
        orchestrator.updateCallStatus(VoiceCallStatus.Ending)
        orchestrator.setMuted(true)

        assertEquals(VoiceCallStatus.Ending, orchestrator.state.value.call)
        assertEquals(listOf(true), session.mutedValues)
        releaseCommand.countDown()
        interrupt.await()
        appJob.cancel()
    }
}
