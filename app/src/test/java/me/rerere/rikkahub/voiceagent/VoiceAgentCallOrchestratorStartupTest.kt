package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallOrchestratorStartupTest {
    @Test
    fun `active identity publishes conversation and transport as one value`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata)
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val request = orchestratorRequest("typed-active-identity").copy(
            transport = VoiceAgentTransport.LiveKitExperimental,
        )

        val result = async { orchestrator.start(request) }
        runCurrent()

        assertTrue(result.await() is VoiceAgentCallStartResult.Active)
        assertEquals(
            ActiveVoiceAgentIdentity(request.conversationId, request.transport),
            orchestrator.activeIdentity.value,
        )
        appJob.cancel()
    }

    @Test
    fun `operation admission creates no child and repeated start attaches one worker outside admission`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + dispatcher)
        val resolverEntered = CompletableDeferred<Unit>()
        val factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") }
        val outcomes = mutableListOf<VoiceAgentStartOutcome>()

        val operation = voiceAgentStartOperation(
            request = orchestratorRequest("operation-admission"),
            appScope = appScope,
            factory = factory,
            resolveRoute = {
                resolverEntered.complete(Unit)
                awaitCancellation()
            },
            onFinished = { _, outcome -> outcomes += outcome },
            onSessionState = { _, _, _ -> },
        )

        assertTrue(operation.phase is VoiceAgentStartPhase.Admitted)
        assertEquals(0, appJob.children.count())

        operation.start()
        operation.start()

        assertEquals(1, appJob.children.count())
        assertFalse(resolverEntered.isCompleted)
        runCurrent()
        assertTrue(resolverEntered.isCompleted)

        operation.cancel()
        assertEquals(VoiceAgentCleanupResult.Completed, operation.cleanup.run(VoiceAgentCleanupMode.Immediate))
        runCurrent()
        assertEquals(listOf(VoiceAgentStartOutcome.Cancelled), outcomes)
        assertEquals(0, factory.calls)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `stale admitted cancellation before start allocates no job and performs no startup work`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        var routeCalls = 0
        val factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") }
        val operation = voiceAgentStartOperation(
            request = orchestratorRequest("stale-admitted"),
            appScope = CoroutineScope(appJob + dispatcher),
            factory = factory,
            resolveRoute = {
                routeCalls += 1
                error("route must not resolve")
            },
            onFinished = { _, _ -> error("cancel-before-start must not finish startup") },
            onSessionState = { _, _, _ -> },
        )
        val stale = reduceVoiceAgentCallState(
            VoiceAgentCallState.Idle,
            VoiceAgentCallEvent.StartAdmitted(Any(), operation),
        )

        assertEquals(
            listOf(
                VoiceAgentCallEffect.CancelStart(operation),
                VoiceAgentCallEffect.RunCleanup(operation.cleanup, VoiceAgentCleanupMode.Immediate),
            ),
            stale.effects,
        )
        (stale.effects[0] as VoiceAgentCallEffect.CancelStart).operation.cancel()
        val cleanup = stale.effects[1] as VoiceAgentCallEffect.RunCleanup
        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.cleanup.run(cleanup.mode))
        operation.start()
        runCurrent()

        assertEquals(0, routeCalls)
        assertEquals(0, factory.calls)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `idle start publishes one complete active call after route factory and session start`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + dispatcher)
        val routeGate = CompletableDeferred<Unit>()
        val route = OrchestratorFakeRoute()
        val lease = route.lease
        val initialState = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        val session = OrchestratorFakeSession(initialState, lease.metadata)
        val factory = OrchestratorFakeFactory { _, _, _ ->
            VoiceAgentSessionCreationResult.Created(session)
        }
        var routeCalls = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = {
                routeCalls += 1
                routeGate.await()
                lease
            },
            appScope = appScope,
        )
        val request = orchestratorRequest("happy")

        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        val result = async { orchestrator.start(request) }
        runCurrent()

        assertEquals(VoiceAgentCallLifecycle.Starting(request.conversationId), orchestrator.lifecycle.value)
        assertEquals(1, routeCalls)
        assertEquals(0, factory.calls)
        assertFalse(result.isCompleted)

        routeGate.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Active(lease.metadata), result.await())
        assertEquals(VoiceAgentCallLifecycle.Active(request.conversationId), orchestrator.lifecycle.value)
        assertEquals(
            ActiveVoiceAgentIdentity(request.conversationId, request.transport),
            orchestrator.activeIdentity.value,
        )
        assertEquals(initialState.copy(call = VoiceCallStatus.BackgroundCapable), orchestrator.state.value)
        assertEquals(1, routeCalls)
        assertEquals(1, factory.calls)
        assertSame(lease, factory.leases.single())
        assertEquals(1, session.startCalls)
        assertEquals(1, session.collectorCount())
        assertEquals(0, route.retirementCalls)

        appJob.cancel()
    }

    @Test
    fun `orchestrator supplies its drain timeout to owned session creation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata)
        val factory = OrchestratorFakeFactory { _, _, _ ->
            VoiceAgentSessionCreationResult.Created(session)
        }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
            endDrainTimeoutMillis = 37,
        )

        val result = async { orchestrator.start(orchestratorRequest("configured-drain-timeout")) }
        runCurrent()

        assertTrue(result.await() is VoiceAgentCallStartResult.Active)
        assertEquals(listOf(37L), factory.endDrainTimeouts)
        appJob.cancel()
    }

    @Test
    fun `matching starting and active requests share the installed call`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + dispatcher)
        val factoryGate = CompletableDeferred<Unit>()
        val route = OrchestratorFakeRoute()
        val lease = route.lease
        val session = OrchestratorFakeSession(routeMetadata = lease.metadata)
        val factory = OrchestratorFakeFactory { _, _, _ ->
            factoryGate.await()
            VoiceAgentSessionCreationResult.Created(session)
        }
        var routeCalls = 0
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = {
                routeCalls += 1
                lease
            },
            appScope = appScope,
        )
        val request = orchestratorRequest("matching")

        val first = async { orchestrator.start(request) }
        runCurrent()
        val matchingStarting = async { orchestrator.start(request) }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(matchingStarting.isCompleted)
        assertEquals(1, routeCalls)
        assertEquals(1, factory.calls)

        factoryGate.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Active(lease.metadata), first.await())
        assertEquals(VoiceAgentCallStartResult.Active(lease.metadata), matchingStarting.await())

        val matchingActive = async { orchestrator.start(request) }
        runCurrent()

        assertTrue(matchingActive.isCompleted)
        assertEquals(VoiceAgentCallStartResult.Active(lease.metadata), matchingActive.await())
        assertEquals(1, routeCalls)
        assertEquals(1, factory.calls)
        assertEquals(1, session.startCalls)
        assertEquals(0, route.retirementCalls)

        appJob.cancel()
    }

    @Test
    fun `route resolution failure returns failed and restores idle without factory ownership`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val routeError = IllegalStateException("route failed")
        val factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { throw routeError },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("route-failure")) }
        runCurrent()

        assertSame(routeError, (result.await() as VoiceAgentCallStartResult.Failed).error)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(null, orchestrator.activeIdentity.value)
        assertEquals(0, factory.calls)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `factory clean failure returns failed and restores idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val creationError = IllegalArgumentException("clean factory failure")
        val factory = OrchestratorFakeFactory { _, lease, _ ->
            lease.retire()
            VoiceAgentSessionCreationResult.FailedClean(creationError)
        }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("factory-clean")) }
        runCurrent()

        assertSame(creationError, (result.await() as VoiceAgentCallStartResult.Failed).error)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(1, route.retirementCalls)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `factory dirty failure publishes exact cleanup failure ownership`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val creationError = IllegalStateException("dirty factory failure")
        val cleanup = OrchestratorFakeCleanupOperation {
            VoiceAgentCleanupResult.Failed(IllegalStateException("still dirty"))
        }
        val factory = OrchestratorFakeFactory { _, _, _ ->
            VoiceAgentSessionCreationResult.FailedDirty(creationError, cleanup)
        }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("factory-dirty")) }
        runCurrent()

        assertSame(creationError, (result.await() as VoiceAgentCallStartResult.Failed).error)
        assertEquals(VoiceAgentCallLifecycle.CleanupFailed(creationError), orchestrator.lifecycle.value)
        assertTrue(cleanup.modes.isEmpty())
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `session start failure cleans the owned session before returning failed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val startError = IllegalStateException("session start failed")
        val cleanup = OrchestratorFakeCleanupOperation()
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onStart = { throw startError },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val result = async { orchestrator.start(orchestratorRequest("session-failure")) }
        runCurrent()

        assertSame(startError, (result.await() as VoiceAgentCallStartResult.Failed).error)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `resource cancellation with failed cleanup publishes canonical dirty failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("session resource cancelled")
        val wrapper = CancellationException(cancellation.message).apply { initCause(cancellation) }
        val cleanupError = IllegalStateException("resource cleanup failed")
        val cleanup = OrchestratorFakeCleanupOperation {
            VoiceAgentCleanupResult.Failed(cleanupError)
        }
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onStart = { throw wrapper },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val failure = CompletableDeferred<Throwable>()
        launch {
            try {
                orchestrator.start(orchestratorRequest("resource-cancel-dirty"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }
        runCurrent()

        val thrown = failure.await()
        assertSame(cancellation, thrown)
        assertEquals(listOf(cleanupError), thrown.suppressed.toList())
        assertEquals(VoiceAgentCallLifecycle.CleanupFailed(cancellation), orchestrator.lifecycle.value)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `caller cancellation joins blocked resource cleanup failure without retrying it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val resourceCancellation = CancellationException("session resource cancelled")
        val callerCancellation = CancellationException("caller cancelled during resource cleanup")
        val cleanupError = IllegalStateException("first resource cleanup failed")
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleanupAttempt = 0
        val cleanup = OrchestratorFakeCleanupOperation {
            cleanupAttempt += 1
            if (cleanupAttempt == 1) {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                VoiceAgentCleanupResult.Failed(cleanupError)
            } else {
                VoiceAgentCleanupResult.Completed
            }
        }
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onStart = { throw resourceCancellation },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val failure = CompletableDeferred<Throwable>()
        val caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-during-resource-cleanup"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()
        cleanupEntered.await()
        caller.cancel(callerCancellation)
        runCurrent()

        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(VoiceAgentCallLifecycle.Stopping(null), orchestrator.lifecycle.value)

        releaseCleanup.complete(Unit)
        runCurrent()

        val thrown = failure.await()
        assertSame(callerCancellation, thrown)
        assertEquals(listOf(cleanupError), thrown.suppressed.toList())
        assertEquals(VoiceAgentCallLifecycle.CleanupFailed(cleanupError), orchestrator.lifecycle.value)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `final pre-collector resource cancellation cleans once without starting collector or leaking job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("pre-collector resource cancelled")
        val cleanup = OrchestratorFakeCleanupOperation()
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onRouteMetadataRead = { throw cancellation },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )

        val failure = CompletableDeferred<Throwable>()
        launch {
            try {
                orchestrator.start(orchestratorRequest("pre-collector-resource-cancel"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }
        runCurrent()

        assertSame(cancellation, failure.await())
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(0, session.collectorCount())
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `caller cancellation while resolving a route preserves the canonical cancellation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val cancellation = CancellationException("route caller cancelled")
        val failure = CompletableDeferred<Throwable>()
        lateinit var caller: Job
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") },
            resolveRoute = {
                caller.cancel(cancellation)
                awaitCancellation()
            },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-route"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()

        assertSame(cancellation, failure.await())
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `caller cancellation as route resolution returns retires the returned lease`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("resolved route caller cancelled")
        val failure = CompletableDeferred<Throwable>()
        lateinit var caller: Job
        val factory = OrchestratorFakeFactory { _, _, _ -> error("factory must not run") }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = {
                caller.cancel(cancellation)
                route.lease
            },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-resolved-route"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()

        assertSame(cancellation, failure.await())
        assertEquals(1, route.retirementCalls)
        assertEquals(0, factory.calls)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `caller cancellation while factory owns route retires that exact route`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("factory caller cancelled")
        val failure = CompletableDeferred<Throwable>()
        lateinit var caller: Job
        val factory = OrchestratorFakeFactory { _, _, _ ->
            caller.cancel(cancellation)
            awaitCancellation()
        }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-factory"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()

        assertSame(cancellation, failure.await())
        assertEquals(1, route.retirementCalls)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `caller cancellation as factory returns cleans session without starting it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("created session caller cancelled")
        val failure = CompletableDeferred<Throwable>()
        val cleanup = OrchestratorFakeCleanupOperation()
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
        )
        lateinit var caller: Job
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                caller.cancel(cancellation)
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-created-session"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()

        assertSame(cancellation, failure.await())
        assertEquals(0, session.startCalls)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `caller cancellation after session start cleans session before throwing canonical cancellation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("session caller cancelled")
        val failure = CompletableDeferred<Throwable>()
        val cleanup = OrchestratorFakeCleanupOperation()
        lateinit var caller: Job
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onStart = { caller.cancel(cancellation) },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-session"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()

        assertSame(cancellation, failure.await())
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `last waiter cancellation publishes first cleanup failure without retrying it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("last waiter cancelled")
        val cleanupError = IllegalStateException("session cleanup failed")
        val cleanupResults = ArrayDeque<VoiceAgentCleanupResult>(
            listOf(
                VoiceAgentCleanupResult.Failed(cleanupError),
                VoiceAgentCleanupResult.Completed,
            ),
        )
        val cleanup = OrchestratorFakeCleanupOperation { cleanupResults.removeFirst() }
        val failure = CompletableDeferred<Throwable>()
        lateinit var caller: Job
        val session = OrchestratorFakeSession(
            routeMetadata = route.lease.metadata,
            cleanupOperation = cleanup,
            onStart = { caller.cancel(cancellation) },
        )
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        caller = launch {
            try {
                orchestrator.start(orchestratorRequest("cancel-cleanup-failure"))
            } catch (error: Throwable) {
                failure.complete(error)
            }
        }

        runCurrent()

        val thrown = failure.await()
        assertSame(cancellation, thrown)
        assertEquals(listOf(cleanupError), thrown.suppressed.toList())
        assertEquals(VoiceAgentCallLifecycle.CleanupFailed(cleanupError), orchestrator.lifecycle.value)
        assertEquals(listOf(VoiceAgentCleanupMode.Immediate), cleanup.modes)
        assertEquals(0, appJob.children.count())
    }

    @Test
    fun `cancelling one matching waiter leaves shared startup running`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val factoryGate = CompletableDeferred<Unit>()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata)
        val factory = OrchestratorFakeFactory { _, _, _ ->
            factoryGate.await()
            VoiceAgentSessionCreationResult.Created(session)
        }
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = factory,
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val request = orchestratorRequest("cancel-one")
        val cancellation = CancellationException("one waiter cancelled")
        val firstFailure = CompletableDeferred<Throwable>()
        val first = launch {
            try {
                orchestrator.start(request)
            } catch (error: Throwable) {
                firstFailure.complete(error)
            }
        }
        val second = async { orchestrator.start(request) }
        runCurrent()

        first.cancel(cancellation)
        runCurrent()

        assertSame(cancellation, firstFailure.await())
        assertFalse(second.isCompleted)
        assertEquals(1, factory.calls)
        assertEquals(1, appJob.children.count())

        factoryGate.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Active(route.lease.metadata), second.await())
        assertEquals(1, session.startCalls)
        appJob.cancel()
    }

    @Test
    fun `discarded pending replacements allocate no additional app scope call job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val firstRoute = OrchestratorFakeRoute()
        val replacementRoute = OrchestratorFakeRoute()
        val cleanupGate = CompletableDeferred<Unit>()
        val firstCleanup = OrchestratorFakeCleanupOperation {
            cleanupGate.await()
            VoiceAgentCleanupResult.Completed
        }
        val firstSession = OrchestratorFakeSession(
            routeMetadata = firstRoute.lease.metadata,
            cleanupOperation = firstCleanup,
        )
        val replacementSession = OrchestratorFakeSession(routeMetadata = replacementRoute.lease.metadata)
        val sessions = ArrayDeque(listOf(firstSession, replacementSession))
        val routes = ArrayDeque(listOf(firstRoute.lease, replacementRoute.lease))
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(sessions.removeFirst())
            },
            resolveRoute = { routes.removeFirst() },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val first = async { orchestrator.start(orchestratorRequest("first")) }
        runCurrent()
        assertTrue(first.await() is VoiceAgentCallStartResult.Active)

        val second = async { orchestrator.start(orchestratorRequest("second")) }
        runCurrent()
        val childrenWhileCleaning = appJob.children.count()
        val thirdRequest = orchestratorRequest("third")
        val third = async { orchestrator.start(thirdRequest) }
        val matchingThird = async { orchestrator.start(thirdRequest) }
        runCurrent()

        assertEquals(childrenWhileCleaning, appJob.children.count())
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        assertFalse(matchingThird.isCompleted)

        cleanupGate.complete(Unit)
        runCurrent()

        assertEquals(VoiceAgentCallStartResult.Superseded, second.await())
        assertEquals(VoiceAgentCallStartResult.Active(replacementRoute.lease.metadata), third.await())
        assertEquals(VoiceAgentCallStartResult.Active(replacementRoute.lease.metadata), matchingThird.await())
        appJob.cancel()
    }

    @Test
    fun `end resumed before lazy collector execution prevents stale publication`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appJob = SupervisorJob()
        val route = OrchestratorFakeRoute()
        val session = OrchestratorFakeSession(routeMetadata = route.lease.metadata)
        val orchestrator = VoiceAgentCallOrchestrator(
            factory = OrchestratorFakeFactory { _, _, _ ->
                VoiceAgentSessionCreationResult.Created(session)
            },
            resolveRoute = { route.lease },
            appScope = CoroutineScope(appJob + dispatcher),
        )
        val finished = async {
            val result = orchestrator.start(orchestratorRequest("collector-race"))
            val end = orchestrator.end()
            result to end
        }

        runCurrent()

        assertTrue(finished.await().first is VoiceAgentCallStartResult.Active)
        assertEquals(VoiceAgentCallEndResult.Completed, finished.await().second)
        assertEquals(VoiceAgentCallLifecycle.Idle, orchestrator.lifecycle.value)
        assertEquals(null, orchestrator.activeIdentity.value)
        assertEquals(VoiceAgentUiState(), orchestrator.state.value)
        assertEquals(0, session.collectorCount())
        assertTrue(appJob.children.none())
    }
}
