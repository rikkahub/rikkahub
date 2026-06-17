package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.Job
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.uuid.Uuid

/**
 * Regression test for the "completed-reconnect treated active" self-heal stall (audit C13b, sub-issue 1).
 *
 * `callTool` reads `reconnectJobs[id]` as "a reconnect is in flight" and `callToolWithHeal` join()s that
 * job instead of healing. A reconnect job was only ever removed from the map on explicit cancel/remove,
 * so a COMPLETED job lingered: the next transport drop read that stale job, the join() returned instantly,
 * heal() was skipped, and the retry ran against the still-dead transport — self-heal silently stopped.
 *
 * `trackActiveReconnectJob` fixes the data structure at the root: a job drops ITSELF from the map on
 * completion (value-conditionally, so a newer rescheduled job is never evicted), making the map's
 * invariant "holds only genuinely-active reconnects" true for every reader. These tests use real
 * [CompletableJob]s and `complete()` (which fires completion handlers synchronously on the calling
 * thread) so the invariant is asserted deterministically with no dispatcher race.
 */
class McpReconnectJobLifecycleTest {

    /** INVARIANT: an active job is present; once it completes it is gone — the map holds only active jobs. */
    @Test
    fun completedJob_removesItselfFromMap() {
        val jobs: ConcurrentMap<Uuid, Job> = ConcurrentHashMap()
        val key = Uuid.random()
        val job = Job()

        trackActiveReconnectJob(jobs, key, job)
        assertSame("an active reconnect is visible as in-flight", job, jobs[key])

        job.complete()
        assertNull("a completed reconnect must not linger in the map", jobs[key])
    }

    /** BOUNDARY: cancellation (not just normal completion) also drops the job — no stale cancelled job. */
    @Test
    fun cancelledJob_removesItselfFromMap() {
        val jobs: ConcurrentMap<Uuid, Job> = ConcurrentHashMap()
        val key = Uuid.random()
        val job = Job()

        trackActiveReconnectJob(jobs, key, job)
        job.cancel()
        assertNull("a cancelled reconnect must not linger in the map", jobs[key])
    }

    /** METAMORPHIC: a newer rescheduled job replaces the old one; the old job's completion must NOT evict it. */
    @Test
    fun olderJobCompletion_doesNotEvictNewerRescheduledJob() {
        val jobs: ConcurrentMap<Uuid, Job> = ConcurrentHashMap()
        val key = Uuid.random()
        val older = Job()
        val newer = Job()

        trackActiveReconnectJob(jobs, key, older)
        // The failure path reschedules: a fresh job is stored before the old one's completion handler runs.
        trackActiveReconnectJob(jobs, key, newer)
        assertSame("the newer reconnect is the in-flight one", newer, jobs[key])

        older.complete()
        assertSame("the older job's completion must not evict the newer reconnect", newer, jobs[key])

        newer.complete()
        assertNull("once the newer reconnect completes the map is empty", jobs[key])
    }

    /**
     * INVARIANT (teardown): cancelTrackedReconnectJob detaches AND cancels whatever job is currently
     * mapped — a non-atomic read-then-cancel-then-key-remove could evict a concurrently-stored active job
     * without cancelling it (orphaned live reconnect). Here it must both clear the map and cancel the job.
     */
    @Test
    fun cancelTrackedReconnectJob_detachesAndCancelsTheMappedJob() {
        val jobs: ConcurrentMap<Uuid, Job> = ConcurrentHashMap()
        val key = Uuid.random()
        val job = Job()

        trackActiveReconnectJob(jobs, key, job)
        cancelTrackedReconnectJob(jobs, key)

        assertNull("the reconnect is removed from the map", jobs[key])
        assertTrue("the detached reconnect is cancelled, not left orphaned-active", job.isCancelled)
    }

    /** BOUNDARY: cancelling an empty slot is a harmless no-op (no NPE). */
    @Test
    fun cancelTrackedReconnectJob_onEmptySlot_isNoOp() {
        val jobs: ConcurrentMap<Uuid, Job> = ConcurrentHashMap()
        cancelTrackedReconnectJob(jobs, Uuid.random())
        // no exception, nothing to assert beyond reaching here
    }

    /**
     * METAMORPHIC (the round-1 orphan race): a fresh active job stored in the cancel/remove window must
     * not be left removed-but-uncancelled. [RemoveInterleavingMap] deterministically reproduces the
     * interleaving — it stores a replacement job at the START of remove(key), exactly when a concurrent
     * scheduleReconnect (arbitrary-thread transport callback) would. The atomic remove(key)?.cancel()
     * cancels whatever it actually removes; the old non-atomic read-cancel-then-key-remove cancelled the
     * pre-window job and removed (orphaned) the replacement, so this test fails before the fix.
     */
    @Test
    fun cancelTrackedReconnectJob_doesNotOrphanAJobStoredInTheRemoveWindow() {
        val backing: ConcurrentMap<Uuid, Job> = ConcurrentHashMap()
        val key = Uuid.random()
        val preWindow = Job()
        val replacement = Job()
        backing[key] = preWindow
        val map = RemoveInterleavingMap(backing) { backing[key] = replacement }

        cancelTrackedReconnectJob(map, key)

        assertNull("the slot is cleared", map[key])
        assertTrue("the job actually removed (the replacement) is cancelled, not orphaned", replacement.isCancelled)
    }

    /**
     * A [ConcurrentMap] that runs a one-shot side effect at the START of the first remove(key) — used to
     * deterministically inject a concurrent store into the cancel/remove window so the orphan race is
     * reproducible without real thread timing.
     */
    private class RemoveInterleavingMap<K : Any, V : Any>(
        private val delegate: ConcurrentMap<K, V>,
        private val onFirstRemove: () -> Unit,
    ) : ConcurrentMap<K, V> by delegate {
        private var fired = false
        override fun remove(key: K): V? {
            if (!fired) {
                fired = true
                onFirstRemove()
            }
            return delegate.remove(key)
        }
    }
}
