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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallOrchestratorConcurrencyTest {
    @Test
    fun `matching livekit transport reuses active call`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata)
        val factory = OrchestratorFakeFactory { _, _, _ ->
            VoiceAgentSessionCreationResult.Created(session)
        }
        var routeCalls = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = {
                routeCalls += 1
                route.lease
            },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val request = orchestratorRequest("livekit-reuse").copy(
            transport = VoiceAgentTransport.LiveKitExperimental,
        )

        assertTrue(
            async { orchestrator.start(request) }.also { runCurrent() }.await() is VoiceAgentCallStartResult.Active,
        )
        assertTrue(
            async { orchestrator.start(request) }.also { runCurrent() }.await() is VoiceAgentCallStartResult.Active,
        )

        assertEquals(listOf(request), factory.requests)
        assertEquals(1, routeCalls)
        assertEquals(0, session.reconnectCalls)
        appJob.cancel()
    }

    @Test
    fun `different transport replaces matching conversation and config and rejects stale direct state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val directCleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            VoiceAgentCleanupResult.Completed
        }
        val routes = listOf(OrchestratorFakeRoute(), OrchestratorFakeRoute())
        val directSession = OrchestratorFakeSession(
            routeMetadata = routes[0].lease.metadata,
            cleanupOperation = directCleanup,
        )
        val liveKitSession = OrchestratorFakeSession(routeMetadata = routes[1].lease.metadata)
        val sessions = listOf(directSession, liveKitSession)
        var routeIndex = 0
        var sessionIndex = 0
        val factory = OrchestratorFakeFactory { _, _, _ ->
            VoiceAgentSessionCreationResult.Created(sessions[sessionIndex++])
        }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { routes[routeIndex++].lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val direct = orchestratorRequest("same").copy(transport = VoiceAgentTransport.DirectGemini)
        val liveKit = direct.copy(transport = VoiceAgentTransport.LiveKitExperimental)

        assertTrue(
            async { orchestrator.start(direct) }.also { runCurrent() }.await() is VoiceAgentCallStartResult.Active,
        )
        val replacement = async { orchestrator.start(liveKit) }
        runCurrent()
        cleanupEntered.await()

        assertFalse(replacement.isCompleted)
        assertEquals(listOf(direct), factory.requests)

        releaseCleanup.complete(Unit)
        runCurrent()

        assertTrue(replacement.await() is VoiceAgentCallStartResult.Active)
        assertEquals(listOf(direct, liveKit), factory.requests)
        assertEquals(listOf(VoiceAgentCleanupMode.Replacement), directCleanup.modes)
        val liveKitState = orchestrator.state.value

        directSession.emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("stale direct callback")))
        runCurrent()

        assertEquals(liveKitState, orchestrator.state.value)
        appJob.cancel()
    }

    @Test
    fun `A B C replacement cleans A once and admits only C after cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            VoiceAgentCleanupResult.Completed
        }
        val routes = listOf(OrchestratorFakeRoute(), OrchestratorFakeRoute())
        val sessions = listOf(
            OrchestratorFakeSession(routeMetadata = routes[0].lease.metadata, cleanupOperation = cleanup),
            OrchestratorFakeSession(routeMetadata = routes[1].lease.metadata),
        )
        var routeCalls = 0
        var created = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(sessions[created++])
            },
            resolveRoute = { routes[routeCalls++].lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val requestA = orchestratorRequest("a")
        val requestB = orchestratorRequest("b")
        val requestC = orchestratorRequest("c")

        assertTrue(
            async { orchestrator.start(requestA) }.also { runCurrent() }.await() is VoiceAgentCallStartResult.Active,
        )
        val childrenBeforeB = appJob.children.count()
        val startB = async { orchestrator.start(requestB) }
        runCurrent()
        cleanupEntered.await()
        val childrenAfterB = appJob.children.count()
        val startC = async { orchestrator.start(requestC) }
        runCurrent()
        val childrenAfterC = appJob.children.count()

        assertFalse(startB.isCompleted)
        assertFalse(startC.isCompleted)
        assertEquals(childrenBeforeB + 1, childrenAfterB)
        assertEquals(childrenAfterB, childrenAfterC)
        assertEquals(1, routeCalls)
        assertEquals(1, created)

        releaseCleanup.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Superseded, startB.await())
        assertEquals(VoiceAgentCallStartResult.Active(routes[1].lease.metadata), startC.await())
        assertEquals(listOf(VoiceAgentCleanupMode.Replacement), cleanup.modes)
        assertEquals(2, routeCalls)
        assertEquals(2, created)
        assertEquals(
            ActiveVoiceAgentIdentity(requestC.conversationId, requestC.transport),
            orchestrator.activeIdentity.value,
        )

        val currentState = orchestrator.state.value
        sessions[0].emit(VoiceAgentUiState(session = VoiceSessionStatus.Error("stale A")))
        runCurrent()
        assertEquals(
            ActiveVoiceAgentIdentity(requestC.conversationId, requestC.transport),
            orchestrator.activeIdentity.value,
        )
        assertEquals(currentState, orchestrator.state.value)
        appJob.cancel()
    }

    @Test
    fun `cleanup failure rejects replacement and one later start retries exact owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val failure = IllegalStateException("route retirement failed")
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var failCleanup = true
        val cleanup = OrchestratorFakeCleanupOperation {
            if (failCleanup) {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                VoiceAgentCleanupResult.Failed(failure)
            } else {
                VoiceAgentCleanupResult.Completed
            }
        }
        val routes = listOf(OrchestratorFakeRoute(), OrchestratorFakeRoute())
        val sessions = listOf(
            OrchestratorFakeSession(routeMetadata = routes[0].lease.metadata, cleanupOperation = cleanup),
            OrchestratorFakeSession(routeMetadata = routes[1].lease.metadata),
        )
        var routeCalls = 0
        var created = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(sessions[created++])
            },
            resolveRoute = { routes[routeCalls++].lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        assertTrue(
            async { orchestrator.start(orchestratorRequest("predecessor")) }
                .also { runCurrent() }
                .await() is VoiceAgentCallStartResult.Active,
        )

        val displaced = async { orchestrator.start(orchestratorRequest("displaced")) }
        runCurrent()
        cleanupEntered.await()
        val childrenAfterDisplaced = appJob.children.count()
        val rejected = async { orchestrator.start(orchestratorRequest("rejected")) }
        runCurrent()

        assertFalse(displaced.isCompleted)
        assertFalse(rejected.isCompleted)
        assertEquals(childrenAfterDisplaced, appJob.children.count())
        assertEquals(1, routeCalls)
        assertEquals(1, created)

        releaseCleanup.complete(Unit)

        assertEquals(VoiceAgentCallStartResult.Superseded, displaced.await())
        assertSame(failure, (rejected.await() as VoiceAgentCallStartResult.Failed).error)
        assertEquals(VoiceAgentCallLifecycle.CleanupFailed(failure), orchestrator.lifecycle.value)
        assertNull(orchestrator.activeIdentity.value)
        assertEquals(1, routeCalls)
        assertEquals(1, created)
        assertEquals(listOf(VoiceAgentCleanupMode.Replacement), cleanup.modes)

        val failedEnd = orchestrator.end()
        assertSame(failure, (failedEnd as VoiceAgentCallEndResult.Failed).error)
        assertEquals(listOf(VoiceAgentCleanupMode.Replacement), cleanup.modes)

        failCleanup = false
        val retryRequest = orchestratorRequest("retry")
        val retry = async { orchestrator.start(retryRequest) }
        runCurrent()

        assertTrue(retry.await() is VoiceAgentCallStartResult.Active)
        assertEquals(
            listOf(VoiceAgentCleanupMode.Replacement, VoiceAgentCleanupMode.Immediate),
            cleanup.modes,
        )
        assertEquals(2, routeCalls)
        assertEquals(2, created)
        assertEquals(
            ActiveVoiceAgentIdentity(retryRequest.conversationId, retryRequest.transport),
            orchestrator.activeIdentity.value,
        )
        appJob.cancel()
    }

    @Test
    fun `end during route preparation cancels startup and reaches idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val routeEntered = CompletableDeferred<Unit>()
        val routeGate = CompletableDeferred<Unit>()
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") },
            resolveRoute = {
                routeEntered.complete(Unit)
                routeGate.await()
                error("cancelled route must not return")
            },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val start = async { orchestrator.start(orchestratorRequest("route-phase")) }
        runCurrent()
        routeEntered.await()

        val end = async { orchestrator.end() }
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Superseded, start.await())
        assertEquals(VoiceAgentCallEndResult.Completed, end.await())
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `end during factory creation retires route before reaching idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val factoryEntered = CompletableDeferred<Unit>()
        val factoryGate = CompletableDeferred<Unit>()
        val route = OrchestratorFakeRoute()
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                factoryEntered.complete(Unit)
                factoryGate.await()
                error("cancelled factory must not return")
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val start = async { orchestrator.start(orchestratorRequest("factory-phase")) }
        runCurrent()
        factoryEntered.await()

        val end = async { orchestrator.end() }
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Superseded, start.await())
        assertEquals(VoiceAgentCallEndResult.Completed, end.await())
        assertEquals(1, route.retirementCalls)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `end during blocking session start waits for exact startup cleanup`() = runTest {
        val appJob = SupervisorJob()
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CountDownLatch(1)
        val cleanup = OrchestratorFakeCleanupOperation()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onStart = {
                startEntered.complete(Unit)
                releaseStart.await()
            },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + Dispatchers.Default),
        )
        val start = async(Dispatchers.Default) { orchestrator.start(orchestratorRequest("session-phase")) }
        startEntered.await()

        val end = async(Dispatchers.Default) { orchestrator.end() }
        orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Stopping(null) }
        assertFalse(end.isCompleted)
        releaseStart.countDown()

        assertEquals(VoiceAgentCallStartResult.Superseded, start.await())
        assertEquals(VoiceAgentCallEndResult.Completed, end.await())
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `route cleanup failure retries only the unfinished route before later start`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val routeFailure = IllegalStateException("route retirement failed")
        var routeRetirementFails = true
        val cleanupRoute = OrchestratorFakeRoute {
            if (routeRetirementFails) throw routeFailure
        }
        val delegate = OrchestratorCleanupDelegate()
        val ownedCleanup = voiceAgentSessionCleanupOperation(
            delegate,
            cleanupRoute.lease,
            endDrainTimeoutMillis = 1_000,
        )
        val resolvedRoutes = listOf(OrchestratorFakeRoute(), OrchestratorFakeRoute())
        val sessions = listOf(
            OrchestratorFakeSession(
                routeMetadata = resolvedRoutes[0].lease.metadata,
                cleanupOperation = ownedCleanup,
            ),
            OrchestratorFakeSession(routeMetadata = resolvedRoutes[1].lease.metadata),
        )
        var routeCalls = 0
        var created = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(sessions[created++])
            },
            resolveRoute = { resolvedRoutes[routeCalls++].lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("route-owner")) }.also { runCurrent() }.await()

        val rejected = async { orchestrator.start(orchestratorRequest("blocked-by-route")) }
        val rejectedFailure = (rejected.await() as VoiceAgentCallStartResult.Failed).error

        assertSame(routeFailure, rejectedFailure)
        assertEquals(1, cleanupRoute.retirementCalls)
        assertEquals(0, delegate.endCalls)
        assertEquals(1, delegate.drainCalls)
        assertEquals(0, delegate.closeCalls)
        assertEquals(1, routeCalls)
        assertEquals(1, created)

        routeRetirementFails = false
        val retry = async { orchestrator.start(orchestratorRequest("after-route-retry")) }
        assertTrue(retry.await() is VoiceAgentCallStartResult.Active)

        assertEquals(2, cleanupRoute.retirementCalls)
        assertEquals(0, delegate.endCalls)
        assertEquals(1, delegate.drainCalls)
        assertEquals(0, delegate.closeCalls)
        assertEquals(2, routeCalls)
        assertEquals(2, created)
        appJob.cancel()
    }

    @Test
    fun `delegate drain failure force closes without repeating retired route`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val delegateFailure = IllegalStateException("delegate drain failed")
        val cleanupRoute = OrchestratorFakeRoute()
        val delegate = OrchestratorCleanupDelegate().apply { drainFailure = delegateFailure }
        val ownedCleanup = voiceAgentSessionCleanupOperation(
            delegate,
            cleanupRoute.lease,
            endDrainTimeoutMillis = 1_000,
        )
        val resolvedRoutes = listOf(OrchestratorFakeRoute(), OrchestratorFakeRoute())
        val sessions = listOf(
            OrchestratorFakeSession(
                routeMetadata = resolvedRoutes[0].lease.metadata,
                cleanupOperation = ownedCleanup,
            ),
            OrchestratorFakeSession(routeMetadata = resolvedRoutes[1].lease.metadata),
        )
        var routeCalls = 0
        var created = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(sessions[created++])
            },
            resolveRoute = { resolvedRoutes[routeCalls++].lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("delegate-owner")) }.also { runCurrent() }.await()

        val rejected = async { orchestrator.start(orchestratorRequest("blocked-by-delegate")) }
        val rejectedFailure = (rejected.await() as VoiceAgentCallStartResult.Failed).error

        assertSame(delegateFailure, rejectedFailure.cause)
        assertEquals(1, cleanupRoute.retirementCalls)
        assertEquals(0, delegate.endCalls)
        assertEquals(1, delegate.drainCalls)
        assertEquals(1, delegate.closeCalls)
        assertEquals(1, routeCalls)

        delegate.drainFailure = null
        val retry = async { orchestrator.start(orchestratorRequest("after-delegate-retry")) }
        assertTrue(retry.await() is VoiceAgentCallStartResult.Active)

        assertEquals(1, cleanupRoute.retirementCalls)
        assertEquals(0, delegate.endCalls)
        assertEquals(1, delegate.drainCalls)
        assertEquals(1, delegate.closeCalls)
        assertEquals(2, routeCalls)
        assertEquals(2, created)
        appJob.cancel()
    }

    @Test
    fun `end from idle completes without allocating route factory or app child`() = runTest {
        val appJob = SupervisorJob()
        val factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") }
        var routeCalls = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = {
                routeCalls += 1
                error("route must not resolve")
            },
            appScope = CoroutineScope(appJob + StandardTestDispatcher(testScheduler)),
        )

        assertEquals(VoiceAgentCallEndResult.Completed, orchestrator.end())
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, routeCalls)
        assertEquals(0, factory.calls)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `end during replacement cleanup retires pending start and joins exact cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            VoiceAgentCleanupResult.Completed
        }
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata, cleanupOperation = cleanup)
        var routeCalls = 0
        val factory = OrchestratorFakeFactory { _, _, _ -> VoiceAgentSessionCreationResult.Created(session) }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = {
                routeCalls += 1
                route.lease
            },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("active-before-end")) }.also { runCurrent() }.await()
        val replacement = async { orchestrator.start(orchestratorRequest("pending-before-end")) }
        runCurrent()
        cleanupEntered.await()

        val end = async { orchestrator.end() }
        runCurrent()

        assertFalse(replacement.isCompleted)
        assertFalse(end.isCompleted)
        assertEquals(1, routeCalls)
        assertEquals(1, factory.calls)
        releaseCleanup.complete(Unit)

        assertEquals(VoiceAgentCallStartResult.Superseded, replacement.await())
        assertEquals(VoiceAgentCallEndResult.Completed, end.await())
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(listOf(VoiceAgentCleanupMode.Replacement), cleanup.modes)
        assertEquals(1, routeCalls)
        assertEquals(1, factory.calls)
        appJob.cancel()
    }

    @Test
    fun `repeated end callers join one graceful cleanup result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            entered.complete(Unit)
            release.await()
            VoiceAgentCleanupResult.Completed
        }
        val route = OrchestratorFakeRoute()
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(
                    OrchestratorFakeSession(routeMetadata = route.lease.metadata, cleanupOperation = cleanup),
                )
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("end")) }.also { runCurrent() }.await()

        val first = async { orchestrator.end() }
        runCurrent()
        entered.await()
        val second = async { orchestrator.end() }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(listOf(VoiceAgentCleanupMode.GracefulEnd), cleanup.modes)
        release.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallEndResult.Completed, first.await())
        assertEquals(VoiceAgentCallEndResult.Completed, second.await())
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(listOf(VoiceAgentCleanupMode.GracefulEnd), cleanup.modes)
        appJob.cancel()
    }

    @Test
    fun `start during end cleanup waits then starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            entered.complete(Unit)
            release.await()
            VoiceAgentCleanupResult.Completed
        }
        val routes = listOf(OrchestratorFakeRoute(), OrchestratorFakeRoute())
        val sessions = listOf(
            OrchestratorFakeSession(routeMetadata = routes[0].lease.metadata, cleanupOperation = cleanup),
            OrchestratorFakeSession(routeMetadata = routes[1].lease.metadata),
        )
        var routeCalls = 0
        var created = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(sessions[created++])
            },
            resolveRoute = { routes[routeCalls++].lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("old")) }.also { runCurrent() }.await()
        val end = async { orchestrator.end() }
        runCurrent()
        entered.await()
        val replacement = async { orchestrator.start(orchestratorRequest("new")) }
        runCurrent()

        assertFalse(end.isCompleted)
        assertFalse(replacement.isCompleted)
        assertEquals(1, routeCalls)
        release.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallEndResult.Completed, end.await())
        assertTrue(replacement.await() is VoiceAgentCallStartResult.Active)
        assertEquals(2, routeCalls)
        appJob.cancel()
    }

    @Test
    fun `closeNow returns before blocked immediate cleanup finishes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = OrchestratorFakeCleanupOperation {
            entered.complete(Unit)
            release.await()
            VoiceAgentCleanupResult.Completed
        }
        val route = OrchestratorFakeRoute()
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(
                    OrchestratorFakeSession(routeMetadata = route.lease.metadata, cleanupOperation = cleanup),
                )
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        async { orchestrator.start(orchestratorRequest("close")) }.also { runCurrent() }.await()

        val closeReturned = CompletableDeferred<Unit>()
        val close = async(Dispatchers.Default) {
            orchestrator.closeNow()
            closeReturned.complete(Unit)
        }
        entered.await()
        closeReturned.await()

        assertEquals(VoiceAgentCallLifecycle.Stopping(null), orchestrator.lifecycle.value)
        assertFalse(release.isCompleted)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        release.complete(Unit)
        close.await()
        assertEquals(
            VoiceAgentCallLifecycle.Idle,
            orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
        )
        appJob.cancel()
    }

    @Test
    fun `closeNow does not inherit an unconfined app scope blocked by external cleanup`() = runTest {
        val appJob = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CountDownLatch(1)
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            VoiceAgentCleanupResult.Completed
        }
        val route = OrchestratorFakeRoute()
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(
                    OrchestratorFakeSession(routeMetadata = route.lease.metadata, cleanupOperation = cleanup),
                )
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + Dispatchers.Unconfined),
        )
        assertTrue(orchestrator.start(orchestratorRequest("unconfined")) is VoiceAgentCallStartResult.Active)

        val closeReturned = CompletableDeferred<Unit>()
        val close = async(Dispatchers.Default) {
            orchestrator.closeNow()
            closeReturned.complete(Unit)
        }
        cleanupEntered.await()
        closeReturned.await()

        assertEquals(1L, releaseCleanup.count)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        releaseCleanup.countDown()
        close.await()
        assertEquals(
            VoiceAgentCallLifecycle.Idle,
            orchestrator.lifecycle.first { it == VoiceAgentCallLifecycle.Idle },
        )
        appJob.cancel()
    }
}
