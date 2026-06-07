package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Regression tests for issue #92: [ChatService.getConversationJobs] feeds a `stateIn(...)` in ChatVM.
 *
 * Two distinct invariants are guarded:
 *
 * 1. RECOVERY ON A STABLE TOPOLOGY (the property the bug actually requires). The job flow is
 *    `_sessionsVersion.flatMapLatest { combine(session.generationJob ...) }` over hot, in-memory
 *    StateFlows. A job start/stop on an EXISTING session must be observed WITHOUT any
 *    `_sessionsVersion` bump (sessions are not created/removed). The earlier fix put `catch` on the
 *    inner combine, which completed the inner flow on failure and only rebuilt on a version bump — a
 *    trigger that does NOT fire on job start/stop. That froze the stream. This test exercises the
 *    version-stable hot combine and asserts later transitions are still observed.
 *
 * 2. BOUNDARY SURVIVAL AT stateIn (the #92 fix location). The error boundary lives at the ChatVM
 *    consumer: `getConversationJobs().catch { emit(emptyMap()) }.stateIn(...)`. A single upstream
 *    throw must NOT permanently kill the sharing coroutine; it degrades to emptyMap. A
 *    [CancellationException] must still propagate so structured-concurrency teardown is never swallowed.
 */
class ConversationJobsErrorHandlingTest {

    // Mirror of ChatService.getConversationJobs()'s assembly: version.flatMapLatest over a combine of
    // per-session generationJob StateFlows. Built from the same operators/order production uses so the
    // recovery invariant is exercised, not a hand-copied loop.
    private fun jobsFlow(
        version: MutableStateFlow<Long>,
        sessions: () -> List<Pair<Uuid, MutableStateFlow<Job?>>>,
    ): Flow<Map<Uuid, Job?>> =
        version.flatMapLatest {
            val current = sessions()
            if (current.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(current.map { (id, jobFlow) -> jobFlow.map { job -> id to job } }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }

    @Test
    fun `job start-stop on an existing session is observed without a version bump (recovery)`() = runBlocking {
        val sessionId = Uuid.random()
        val jobState = MutableStateFlow<Job?>(null)
        val version = MutableStateFlow(0L)
        val job = Job()

        val observed = CopyOnWriteArrayList<Map<Uuid, Job?>>()
        val collector = launch {
            jobsFlow(version) { listOf(sessionId to jobState) }.collect { observed.add(it) }
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

    // The exact error boundary ChatVM applies before stateIn: a real throw degrades to emptyMap,
    // a CancellationException is re-thrown so structured-concurrency teardown is never swallowed.
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
