package me.rerere.rikkahub.data.ai.schedule

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.rikkahub.data.repository.ScheduleClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [ScheduleFireRunner] — the policy [ScheduleWorker] delegates to (SPEC.md M5 /
 * task T9). The worker itself is a thin WorkManager [androidx.work.CoroutineWorker] adapter (needs a
 * `Context`/`WorkerParameters`, so it is untestable on the JVM unit path); ALL ordering logic lives
 * in this plain runner so it is driven by fakes with no Android, exactly as the board/schedule
 * repository tests fake Room.
 *
 * The contract these tests pin (the T9 acceptance):
 *  - a null claim (already fired / disabled / not due) is an idempotent no-op — a duplicate worker
 *    must NOT run, finish, or re-enqueue anything;
 *  - a winning claim drives the steps in EXACTLY this order: claim -> run -> finishRun -> re-enqueue;
 *  - the re-enqueue happens IFF the post-claim snapshot is still `enabled` (a recurring schedule that
 *    advanced), and enqueues the ADVANCED `nextFireAt` under the same schedule id (the unique name
 *    dedups). A one-shot (disabled after its claim) is NOT re-enqueued.
 */
class ScheduleWorkerTest {

    /** Records every seam call in order, so a passing test proves real ordering, not a stub touch. */
    private class Trace {
        val steps = mutableListOf<String>()
    }

    private fun snapshot(
        kind: ScheduleKind,
        enabled: Boolean,
        nextFireAt: Long,
    ): ScheduleSnapshot = ScheduleSnapshot(
        id = Uuid.random(),
        targetAssistantId = Uuid.random(),
        prompt = "go",
        owner = ScheduleOwner.USER,
        kind = kind,
        firstFireAt = 1_000L,
        nextFireAt = nextFireAt,
        timeZoneId = "UTC",
        recurrenceSpec = null,
        misfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
        enabled = enabled,
        lastFiredAt = 1_000L,
        lastTaskRunId = null,
        runningTaskRunId = Uuid.random(),
    )

    /**
     * Build a runner whose seams record into [trace]; [claim] is what `claimDue` returns. By default
     * [nextFireIfStillArmed] mirrors the post-claim snapshot (enabled => its advanced nextFireAt), the
     * common case; a test simulating a concurrent pause overrides it to return null.
     */
    private fun runner(
        trace: Trace,
        scheduleId: Uuid,
        parentConversationId: Uuid?,
        claim: ScheduleClaim?,
        terminalRunId: Uuid? = claim?.runId,
        enqueued: MutableList<Pair<Uuid, Long>> = mutableListOf(),
        nextFireIfStillArmed: Long? =
            claim?.snapshot?.takeIf { it.enabled }?.nextFireAt,
    ): ScheduleFireRunner = ScheduleFireRunner(
        claimDue = { id, _ ->
            trace.steps += "claim"
            assertEquals(scheduleId, id)
            claim
        },
        resolveParentConversation = { id ->
            assertEquals(scheduleId, id)
            parentConversationId
        },
        run = { c, parent ->
            trace.steps += "run"
            assertEquals(claim, c)
            assertEquals(parentConversationId, parent)
            terminalRunId
        },
        finishRun = { id, runId, terminal ->
            trace.steps += "finish"
            assertEquals(scheduleId, id)
            assertEquals(claim?.runId, runId)
            assertEquals(terminalRunId, terminal)
        },
        nextFireIfStillArmed = { id ->
            assertEquals(scheduleId, id)
            nextFireIfStillArmed
        },
        enqueue = { id, fireAt ->
            trace.steps += "enqueue"
            enqueued += id to fireAt
        },
        now = { 5_000L },
    )

    @Test
    fun `a null claim is an idempotent no-op`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val runner = runner(trace, scheduleId, Uuid.random(), claim = null)

        runner.fire(scheduleId)

        // A duplicate / already-fired / disabled worker claims, gets null, and stops: no run, no
        // finish, no re-enqueue.
        assertEquals(listOf("claim"), trace.steps)
    }

    @Test
    fun `a winning recurring claim drives claim then run then finish then re-enqueue in order`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val parent = Uuid.random()
        val claim = ScheduleClaim(
            runId = Uuid.random(),
            // recurring + still enabled + advanced nextFireAt => re-enqueue is required.
            snapshot = snapshot(ScheduleKind.RECURRING, enabled = true, nextFireAt = 9_000L),
        )
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val runner = runner(trace, scheduleId, parent, claim, enqueued = enqueued)

        runner.fire(scheduleId)

        assertEquals(listOf("claim", "run", "finish", "enqueue"), trace.steps)
        // The re-enqueue targets the SAME schedule id at the ADVANCED next-fire time.
        assertEquals(1, enqueued.size)
        assertEquals(scheduleId to 9_000L, enqueued.single())
    }

    @Test
    fun `a one-shot claim disables after firing and is not re-enqueued`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val claim = ScheduleClaim(
            runId = Uuid.random(),
            // one-shot => disabled after the claim => MUST NOT re-enqueue.
            snapshot = snapshot(ScheduleKind.ONE_SHOT, enabled = false, nextFireAt = 10_000L),
        )
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val runner = runner(trace, scheduleId, Uuid.random(), claim, enqueued = enqueued)

        runner.fire(scheduleId)

        assertEquals(listOf("claim", "run", "finish"), trace.steps)
        assertTrue("a disabled one-shot must not re-enqueue", enqueued.isEmpty())
    }

    @Test
    fun `a recurring schedule paused mid-fire is NOT re-enqueued`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val parent = Uuid.random()
        // The post-claim snapshot still says enabled (it was captured before the pause), but the user
        // paused the schedule while the worker was running. The fresh read reports the row is no longer
        // armed (null), so the worker must NOT re-arm a future fire for a now-disabled schedule.
        val claim = ScheduleClaim(
            runId = Uuid.random(),
            snapshot = snapshot(ScheduleKind.RECURRING, enabled = true, nextFireAt = 9_000L),
        )
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val runner = runner(
            trace, scheduleId, parent, claim, enqueued = enqueued,
            nextFireIfStillArmed = null,
        )

        runner.fire(scheduleId)

        // claim -> run -> finish, but NO enqueue: the pause's cancellation is not undone by a stale
        // post-claim snapshot.
        assertEquals(listOf("claim", "run", "finish"), trace.steps)
        assertTrue("a paused schedule must not be re-armed by the worker", enqueued.isEmpty())
    }

    @Test
    fun `the re-enqueue uses the fresh fire time, not the stale post-claim snapshot`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val parent = Uuid.random()
        // The post-claim snapshot carried nextFireAt=9_000L, but the current row's fire time is
        // 12_000L (e.g. it was re-armed/advanced again). The worker must enqueue the FRESH time.
        val claim = ScheduleClaim(
            runId = Uuid.random(),
            snapshot = snapshot(ScheduleKind.RECURRING, enabled = true, nextFireAt = 9_000L),
        )
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val runner = runner(
            trace, scheduleId, parent, claim, enqueued = enqueued,
            nextFireIfStillArmed = 12_000L,
        )

        runner.fire(scheduleId)

        assertEquals(listOf("claim", "run", "finish", "enqueue"), trace.steps)
        assertEquals(scheduleId to 12_000L, enqueued.single())
    }

    @Test
    fun `a throwing run still finishes the claim and re-enqueues a recurring schedule, then rethrows`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val parent = Uuid.random()
        val claim = ScheduleClaim(
            runId = Uuid.random(),
            snapshot = snapshot(ScheduleKind.RECURRING, enabled = true, nextFireAt = 9_000L),
        )
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        // run() throws: without try/finally, finishRun would be skipped and the schedule would stay
        // pinned "running" forever while the worker's failure is swallowed on the retry's blocked claim.
        val runner = ScheduleFireRunner(
            claimDue = { _, _ -> trace.steps += "claim"; claim },
            resolveParentConversation = { parent },
            run = { _, _ -> trace.steps += "run"; throw IllegalStateException("model missing") },
            finishRun = { id, runId, terminal ->
                trace.steps += "finish"
                assertEquals(scheduleId, id)
                assertEquals(claim.runId, runId)
                // No real terminal run id was produced; the claim run id stands in.
                assertEquals(claim.runId, terminal)
            },
            // The schedule is still armed; the throwing run must not lose its recurrence.
            nextFireIfStillArmed = { 9_000L },
            enqueue = { id, fireAt -> trace.steps += "enqueue"; enqueued += id to fireAt },
            now = { 5_000L },
        )

        var thrown: Throwable? = null
        try {
            runner.fire(scheduleId)
        } catch (e: Throwable) {
            thrown = e
        }

        // The claim is finished and the recurring schedule re-enqueued EVEN THOUGH run threw, so the
        // marker is released and the next occurrence is not lost...
        assertEquals(listOf("claim", "run", "finish", "enqueue"), trace.steps)
        assertEquals(1, enqueued.size)
        assertEquals(scheduleId to 9_000L, enqueued.single())
        // ...and the failure is surfaced (worker maps it to Result.failure()), never swallowed.
        assertTrue("the run failure must propagate", thrown is IllegalStateException)
    }

    @Test
    fun `a missing parent conversation skips the run but still clears the in-flight marker`() = runBlocking {
        val trace = Trace()
        val scheduleId = Uuid.random()
        val claim = ScheduleClaim(
            runId = Uuid.random(),
            snapshot = snapshot(ScheduleKind.ONE_SHOT, enabled = false, nextFireAt = 10_000L),
        )
        // The bound conversation was deleted between enqueue and fire: no parent to run against.
        val runner = runner(trace, scheduleId, parentConversationId = null, claim = claim, terminalRunId = claim.runId)

        runner.fire(scheduleId)

        // The run is skipped (no parent), but finishRun STILL runs so the claim's running marker is
        // cleared — a killed/orphaned fire must never pin the schedule "running" forever.
        assertEquals(listOf("claim", "finish"), trace.steps)
    }
}
