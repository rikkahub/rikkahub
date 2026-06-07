package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Regression tests for issue #92. Two distinct seams are guarded, each at the layer it lives in:
 *
 * 1. PRODUCTION ASSEMBLY — [assembleConversationJobsFlow], the seam behind
 *    [ChatService.getConversationJobs]. Two invariants are exercised against the REAL production
 *    function (not a hand-copied mirror):
 *      a. RECOVERY ON A STABLE TOPOLOGY: a job start/stop on an EXISTING session must be observed
 *         WITHOUT any `version` bump (sessions are not created/removed). An earlier fix put `catch`
 *         on the inner combine, completing it on failure and only rebuilding on a version bump — a
 *         trigger that never fires on job start/stop — which froze the stream.
 *      b. THROW PROPAGATION: an upstream throw must PROPAGATE out of the assembly, never be rewritten
 *         into an emptyMap "no active jobs" terminal success. Web consumers (`.first()`/SSE) need the
 *         throw to surface as HTTP 500; a swallowing catch here would silently turn that into a false
 *         200 — the exact #92 regression. This test FAILS if anyone re-adds `catch { emit(emptyMap()) }`
 *         inside the production seam.
 *
 * 2. ChatVM BOUNDARY MIRROR — the error boundary the Android consumer applies before `stateIn`:
 *    `getConversationJobs().catch { emit(emptyMap()) }.stateIn(...)`. The boundary genuinely lives in
 *    [me.rerere.rikkahub.ui.pages.chat.ChatVM.conversationJobs], which is not JVM-instantiable here,
 *    so [degradeToEmptyOnError] is a documented mirror of THAT consumer-side boundary: a real throw
 *    degrades to emptyMap; a [CancellationException] is re-thrown so teardown is never swallowed.
 */
class ConversationJobsErrorHandlingTest {

    @Test
    fun `job start-stop on an existing session is observed without a version bump (recovery)`() = runBlocking {
        val sessionId = Uuid.random()
        val jobState = MutableStateFlow<Job?>(null)
        val version = MutableStateFlow(0L)
        val job = Job()

        val observed = CopyOnWriteArrayList<Map<Uuid, Job?>>()
        val collector = launch {
            assembleConversationJobsFlow(version) { listOf(sessionId to jobState) }
                .collect { observed.add(it) }
        }

        // Stable topology: the session set never changes, so version is never bumped. The bug would
        // only surface a transition on a version bump; here every transition must flow purely from the
        // hot generationJob StateFlow.
        awaitUntil { observed.contains(emptyMap<Uuid, Job?>()) }

        jobState.value = job
        awaitUntil { observed.contains(mapOf(sessionId to job)) }

        jobState.value = null
        // After a start, a stop must also be observed — proving the inner combine stayed subscribed to
        // the live StateFlow across transitions, with no version bump in between.
        awaitUntil { observed.lastOrNull() == emptyMap<Uuid, Job?>() }

        collector.cancel()

        assertEquals(
            "job start on the existing session must be observed without a version bump",
            mapOf(sessionId to job),
            observed.first { it.isNotEmpty() }
        )
        assertEquals(
            "job stop on the existing session must be observed without a version bump",
            emptyMap<Uuid, Job?>(),
            observed.last()
        )
    }

    @Test
    fun `getConversationJobs assembly does NOT swallow an upstream throw into a false-success value`() = runBlocking {
        // Drives the PRODUCTION seam with a single session whose generationJob flow emits a real job
        // then throws a NON-cancellation exception. The throw must propagate out of the assembly: the
        // collector terminates by THROWING, and no emptyMap "no active jobs" terminal success is ever
        // emitted after the throw. This is the web HTTP-500 contract. FAILS if `catch { emit(emptyMap()) }`
        // is re-added inside assembleConversationJobsFlow (the #92 regression).
        val sessionId = Uuid.random()
        val job: Job? = Job()
        val throwingJobFlow: Flow<Job?> = flow {
            emit(job)
            throw IllegalStateException("upstream boom")
        }
        val version = MutableStateFlow(0L)

        val observed = CopyOnWriteArrayList<Map<Uuid, Job?>>()
        val threw = AtomicBoolean(false)

        try {
            withTimeout(2_000) {
                assembleConversationJobsFlow(version) { listOf(sessionId to throwingJobFlow) }
                    .collect { observed.add(it) }
            }
        } catch (e: IllegalStateException) {
            threw.set(true)
        }

        assertTrue(
            "upstream throw must PROPAGATE out of the assembly, not be swallowed into a value",
            threw.get()
        )
        assertEquals(
            "the healthy job map must be emitted before the throw",
            mapOf(sessionId to job),
            observed.first()
        )
        assertTrue(
            "an upstream throw must never be rewritten into an emptyMap terminal success (false-200/#92)",
            observed.none { it == emptyMap<Uuid, Job?>() }
        )
    }

    @Test
    fun `inner-catch completion gates recovery on a version bump (the rejected behavior)`() = runBlocking {
        // Deterministic falsifier for the rejected design. The inner flow THROWS, inner catch emits
        // emptyMap and COMPLETES it. With no version bump, flatMapLatest never rebuilds, so a later
        // healthy emission is NEVER observed — proving recovery was gated on the wrong trigger.
        val version = MutableStateFlow(0L)
        val liveId = Uuid.random()
        val liveJob = Job()

        val rejectedDesign: Flow<Map<Uuid, Job?>> = version.flatMapLatest { v ->
            when (v) {
                0L -> flow<Map<Uuid, Job?>> {
                    emit(emptyMap())
                    throw IllegalStateException("inner boom")
                }.catch { emit(emptyMap()) }
                else -> flowOf(mapOf(liveId to (liveJob as Job?)))
            }
        }

        val observed = CopyOnWriteArrayList<Map<Uuid, Job?>>()
        val collector = launch { rejectedDesign.collect { observed.add(it) } }
        awaitUntil { observed.contains(emptyMap<Uuid, Job?>()) }

        // No version bump (mimics a job start/stop on a stable session set). The healthy live map for a
        // later version is never produced because the source is gated on version transitions.
        repeat(50) { yield() }
        collector.cancel()

        assertTrue(
            "rejected design: with no version bump after an inner failure, the live map is never observed",
            observed.none { it == mapOf(liveId to liveJob) }
        )
    }

    // Documented mirror of the ChatVM consumer-side boundary (ChatVM.conversationJobs), which is not
    // JVM-instantiable here: a real throw degrades to emptyMap, a CancellationException is re-thrown so
    // structured-concurrency teardown is never swallowed.
    private fun Flow<Map<Uuid, Job?>>.degradeToEmptyOnError(): Flow<Map<Uuid, Job?>> =
        catch { e ->
            if (e is CancellationException) throw e
            emit(emptyMap())
        }

    @Test
    fun `catch before stateIn keeps the collector alive on a single upstream throw`() = runBlocking {
        // Models the ChatVM boundary: getConversationJobs().degradeToEmptyOnError().stateIn(...).
        // A throwing upstream must degrade to emptyMap rather than killing the sharing coroutine.
        val throwing: Flow<Map<Uuid, Job?>> = flow {
            emit(mapOf(Uuid.random() to (Job() as Job?)))
            throw IllegalStateException("upstream boom")
        }

        val state = throwing
            .degradeToEmptyOnError()
            .stateIn(this, SharingStarted.Eagerly, emptyMap())

        awaitUntil { state.value == emptyMap<Uuid, Job?>() }
        assertEquals(
            "single upstream throw must degrade to empty map, not crash the collector",
            emptyMap<Uuid, Job?>(),
            state.value
        )
    }

    @Test
    fun `cancellation propagates through the boundary catch, never swallowed`() {
        val source: Flow<Map<Uuid, Job?>> = flow { throw CancellationException("cancelled") }
        var rethrown = false
        try {
            runBlocking { source.degradeToEmptyOnError().collect { } }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must propagate, never be converted to a value", rethrown)
    }

    private suspend fun awaitUntil(timeoutMs: Long = 2_000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) yield()
        }
    }
}
