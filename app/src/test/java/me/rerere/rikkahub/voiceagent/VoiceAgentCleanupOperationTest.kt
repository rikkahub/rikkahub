package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentCleanupOperationTest {
    @Test
    fun `reusable joined cleanup primitive shares one in-flight attempt`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var unfinished = true
        val cleanup = object : JoinedCleanupOperation() {
            override suspend fun executeAttempt(mode: VoiceAgentCleanupMode): CleanupAttemptOutcome {
                attempts += 1
                entered.complete(Unit)
                release.await()
                unfinished = false
                return CleanupAttemptFailures().outcome()
            }

            override fun hasUnfinishedStages(): Boolean = unfinished
        }
        val first = async { cleanup.run(VoiceAgentCleanupMode.Immediate) }
        entered.await()
        val second = async { cleanup.run(VoiceAgentCleanupMode.GracefulEnd) }
        runCurrent()

        release.complete(Unit)

        assertSame(VoiceAgentCleanupResult.Completed, first.await())
        assertSame(VoiceAgentCleanupResult.Completed, second.await())
        assertEquals(1, attempts)
    }

    @Test
    fun `failed route retries without repeating successful delegate`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(routeFailure)
        val lease = recordingCleanupRouteLease(events, retirementFailure)
        val delegate = RecordingCleanupSession(events)
        val collector = Job()
        val callJob = Job()
        val cleanup = activeVoiceAgentCallCleanupOperation(
            collector = collector,
            callJob = callJob,
            sessionCleanup = voiceAgentSessionCleanupOperation(delegate, lease, endDrainTimeoutMillis = 100),
        )

        val first = cleanup.run(VoiceAgentCleanupMode.Immediate)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        assertSame(routeFailure, (first as VoiceAgentCleanupResult.Failed).error)
        assertEquals(listOf("route-retire", "session-close-now"), events)
        assertFalse(collector.isActive)
        assertFalse(callJob.isActive)

        events.clear()
        retirementFailure.set(null)

        assertSame(VoiceAgentCleanupResult.Completed, cleanup.run(VoiceAgentCleanupMode.Immediate))
        assertEquals(listOf("route-retire"), events)
    }

    @Test
    fun `failed replacement drain force closes and leaves completed retry as no-op`() = runTest {
        val events = mutableListOf<String>()
        val endFailure = IllegalStateException("session end failed")
        val delegate = RecordingCleanupSession(events, drainFailure = endFailure)
        val cleanup = voiceAgentSessionCleanupOperation(
            delegate = delegate,
            routeLease = recordingCleanupRouteLease(events),
            endDrainTimeoutMillis = 100,
        )

        val first = cleanup.run(VoiceAgentCleanupMode.Replacement)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        assertEquals(endFailure.message, (first as VoiceAgentCleanupResult.Failed).error.message)
        assertEquals(listOf("route-retire", "session-end-and-drain", "session-close-now"), events)

        events.clear()

        assertSame(VoiceAgentCleanupResult.Completed, cleanup.run(VoiceAgentCleanupMode.Replacement))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `replacement drains the session before cancelling its call job`() = runTest {
        val events = mutableListOf<String>()
        val releaseDrain = CompletableDeferred<Unit>()
        val collector = Job().apply { invokeOnCompletion { events += "collector" } }
        val callJob = Job().apply { invokeOnCompletion { events += "call-job" } }
        val cleanup = activeVoiceAgentCallCleanupOperation(
            collector = collector,
            callJob = callJob,
            sessionCleanup = voiceAgentSessionCleanupOperation(
                delegate = RecordingCleanupSession(events, onDrain = { releaseDrain.await() }),
                routeLease = recordingCleanupRouteLease(events),
                endDrainTimeoutMillis = 1_000,
            ),
        )

        val result = async { cleanup.run(VoiceAgentCleanupMode.Replacement) }
        runCurrent()

        assertEquals(listOf("route-retire", "session-end-and-drain"), events)
        assertTrue(result.isActive)
        assertTrue(callJob.isActive)

        releaseDrain.complete(Unit)
        assertSame(VoiceAgentCleanupResult.Completed, result.await())
        assertEquals(
            listOf("route-retire", "session-end-and-drain", "collector", "call-job"),
            events,
        )
    }

    @Test
    fun `concurrent callers join exact cleanup attempt`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val delegate = RecordingCleanupSession(
            events = events,
            onDrain = {
                drainStarted.complete(Unit)
                releaseDrain.await()
            },
        )
        val cleanup = voiceAgentSessionCleanupOperation(
            delegate = delegate,
            routeLease = recordingCleanupRouteLease(events, AtomicReference(routeFailure)),
            endDrainTimeoutMillis = 1_000,
        )
        val first = async { cleanup.run(VoiceAgentCleanupMode.GracefulEnd) }
        drainStarted.await()
        val second = async { cleanup.run(VoiceAgentCleanupMode.Immediate) }
        runCurrent()

        releaseDrain.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()

        assertSame(firstResult, secondResult)
        assertSame(routeFailure, (firstResult as VoiceAgentCleanupResult.Failed).error)
        assertEquals(listOf("route-retire", "session-end-and-drain"), events)
    }

    @Test
    fun `completed stages make retry a no-op`() = runTest {
        val events = mutableListOf<String>()
        val neverCompletes = CompletableDeferred<Unit>()
        val cleanup = voiceAgentSessionCleanupOperation(
            delegate = RecordingCleanupSession(events, onDrain = { neverCompletes.await() }),
            routeLease = recordingCleanupRouteLease(events),
            endDrainTimeoutMillis = 100,
        )
        val first = async { cleanup.run(VoiceAgentCleanupMode.GracefulEnd) }

        advanceTimeBy(100)
        runCurrent()
        val firstResult = first.await()

        assertTrue((firstResult as VoiceAgentCleanupResult.Failed).error is VoiceAgentEndDrainTimeoutException)
        assertEquals(listOf("route-retire", "session-end-and-drain", "session-close-now"), events)

        assertSame(VoiceAgentCleanupResult.Completed, cleanup.run(VoiceAgentCleanupMode.Immediate))
        assertEquals(listOf("route-retire", "session-end-and-drain", "session-close-now"), events)
    }

    @Test
    fun `graceful timeout follows earlier route failure`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(routeFailure)
        val neverCompletes = CompletableDeferred<Unit>()
        val cleanup = voiceAgentSessionCleanupOperation(
            delegate = RecordingCleanupSession(events, onDrain = { neverCompletes.await() }),
            routeLease = recordingCleanupRouteLease(events, retirementFailure),
            endDrainTimeoutMillis = 100,
        )
        val first = async { cleanup.run(VoiceAgentCleanupMode.GracefulEnd) }

        advanceTimeBy(100)
        runCurrent()
        val firstResult = first.await() as VoiceAgentCleanupResult.Failed

        assertSame(routeFailure, firstResult.error)
        assertTrue(routeFailure.suppressed.single() is VoiceAgentEndDrainTimeoutException)
        assertEquals(listOf("route-retire", "session-end-and-drain", "session-close-now"), events)

        events.clear()
        retirementFailure.set(null)
        assertSame(VoiceAgentCleanupResult.Completed, cleanup.run(VoiceAgentCleanupMode.Immediate))
        assertEquals(listOf("route-retire"), events)
    }

    @Test
    fun `cancellation remains canonical with ordered suppressed failures`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val cancellation = CancellationException("cleanup cancelled")
        val closeFailure = IllegalArgumentException("session close failed")
        val cleanup = voiceAgentSessionCleanupOperation(
            delegate = RecordingCleanupSession(
                events = events,
                drainFailure = cancellation,
                closeFailure = closeFailure,
            ),
            routeLease = recordingCleanupRouteLease(events, AtomicReference(routeFailure)),
            endDrainTimeoutMillis = 100,
        )

        val thrown = runCatching {
            cleanup.run(VoiceAgentCleanupMode.GracefulEnd)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(routeFailure, closeFailure), thrown?.suppressed?.toList())
        assertEquals(listOf("route-retire", "session-end-and-drain", "session-close-now"), events)
    }

    @Test
    fun `cancellation arriving during synchronous cleanup remains canonical`() = runTest {
        val events = mutableListOf<String>()
        val retireStarted = CountDownLatch(1)
        val releaseRetire = CountDownLatch(1)
        val lease = recordingCleanupRouteLease(
            events = events,
            onRetire = {
                retireStarted.countDown()
                check(releaseRetire.await(1, TimeUnit.SECONDS)) { "route retirement was not released" }
            },
        )
        val cleanup = voiceAgentRouteCleanupOperation(lease)
        val observed = AtomicReference<Any?>()
        val worker = async(Dispatchers.Default) {
            observed.set(
                runCatching { cleanup.run(VoiceAgentCleanupMode.Immediate) }
                    .fold(onSuccess = { it }, onFailure = { it }),
            )
        }
        assertTrue(retireStarted.await(1, TimeUnit.SECONDS))
        val cancellation = CancellationException("cleanup caller cancelled")

        worker.cancel(cancellation)
        releaseRetire.countDown()
        runCatching { worker.await() }

        assertSame(cancellation, observed.get())
        assertEquals(listOf("route-retire"), events)
    }

    @Test
    fun `active cleanup runs later stages and retries only unfinished session cleanup`() = runTest {
        val events = mutableListOf<String>()
        val sessionFailure = IllegalStateException("session cleanup failed")
        val sessionCleanup = RecordingNestedCleanupOperation(
            events = events,
            results = ArrayDeque(
                listOf(
                    VoiceAgentCleanupResult.Failed(sessionFailure),
                    VoiceAgentCleanupResult.Completed,
                ),
            ),
        )
        val collector = Job().apply { invokeOnCompletion { events += "collector" } }
        val callJob = Job().apply { invokeOnCompletion { events += "call-job" } }
        val cleanup = activeVoiceAgentCallCleanupOperation(collector, callJob, sessionCleanup)

        val first = cleanup.run(VoiceAgentCleanupMode.Immediate)

        assertSame(sessionFailure, (first as VoiceAgentCleanupResult.Failed).error)
        assertEquals(listOf("session", "collector", "call-job"), events)

        events.clear()

        assertSame(VoiceAgentCleanupResult.Completed, cleanup.run(VoiceAgentCleanupMode.Immediate))
        assertEquals(listOf("session"), events)
    }
}

private fun recordingCleanupRouteLease(
    events: MutableList<String>,
    retirementFailure: AtomicReference<Throwable?> = AtomicReference(null),
    onRetire: () -> Unit = {},
): VoiceAgentRouteLease {
    val registry = VoiceAgentTelecomCallRegistry()
    val attempt = registry.beginAttempt().requireAllocatedAttemptId()
    check(
        registry.activate(attempt, RecordingCleanupTelecomCall(events, retirementFailure, onRetire)),
    )
    return registry.consumeActiveOutcome(attempt).requireResolvedLease()
}

private class RecordingCleanupTelecomCall(
    private val events: MutableList<String>,
    private val retirementFailure: AtomicReference<Throwable?>,
    private val onRetire: () -> Unit,
) : VoiceAgentTelecomCall {
    override fun disconnectFromApp() {
        events += "route-retire"
        onRetire()
        retirementFailure.get()?.let { throw it }
    }
}

private class RecordingCleanupSession(
    private val events: MutableList<String>,
    private val endFailure: Throwable? = null,
    private val drainFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
    private val onDrain: suspend () -> Unit = {},
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit

    override fun end() {
        events += "session-end"
        endFailure?.let { throw it }
    }

    override suspend fun endAndDrain() {
        events += "session-end-and-drain"
        onDrain()
        drainFailure?.let { throw it }
    }

    override fun closeNow() {
        events += "session-close-now"
        closeFailure?.let { throw it }
    }
}

private class RecordingNestedCleanupOperation(
    private val events: MutableList<String>,
    private val results: ArrayDeque<VoiceAgentCleanupResult>,
) : VoiceAgentCleanupOperation {
    override val token: Any = Any()

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        events += "session"
        return results.removeFirst()
    }
}
