package me.rerere.rikkahub.data.ai.schedule

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [ScheduleRescheduler] — the cold-start pass [RikkaHubApp] runs after
 * `recoverTasks()` (SPEC.md M6 / task T11). It is pure policy driven by narrow injected seams (a
 * snapshot reader, an orphan-run predicate, an orphan-clear mutation, the enqueue transport), so it
 * is testable on the JVM unit path with fakes — no Android, mirroring [ScheduleWorkerTest].
 *
 * The contract these tests pin (the T11 acceptance):
 *  - every overdue enabled schedule is re-enqueued via the [ScheduleEnqueuer] seam at its
 *    `nextFireAt`, so a fire missed while the process was dead fires on the next opportunity;
 *  - an ORPHAN `running_task_run_id` — one pointing at a run the recovery pass already marked
 *    `Interrupted`, or at a run that no longer exists — is CLEARED, so a killed fire never pins its
 *    schedule "running" forever (the claim filters on `running_task_run_id == null`, so an
 *    uncleared orphan would block every future fire).
 */
class ScheduleReschedulerTest {

    private fun snapshot(
        id: Uuid = Uuid.random(),
        nextFireAt: Long,
        enabled: Boolean = true,
        runningTaskRunId: Uuid? = null,
    ): ScheduleSnapshot = ScheduleSnapshot(
        id = id,
        targetAssistantId = Uuid.random(),
        prompt = "go",
        owner = ScheduleOwner.USER,
        kind = ScheduleKind.RECURRING,
        firstFireAt = 1_000L,
        nextFireAt = nextFireAt,
        timeZoneId = "UTC",
        recurrenceSpec = """{"every":15,"unit":"MINUTES"}""",
        misfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
        enabled = enabled,
        lastFiredAt = 1_000L,
        lastTaskRunId = null,
        runningTaskRunId = runningTaskRunId,
    )

    private fun rescheduler(
        overdue: List<ScheduleSnapshot>,
        running: List<ScheduleSnapshot> = emptyList(),
        orphanRunIds: Set<Uuid> = emptySet(),
        cleared: MutableList<Uuid> = mutableListOf(),
        enqueued: MutableList<Pair<Uuid, Long>> = mutableListOf(),
    ): ScheduleRescheduler = ScheduleRescheduler(
        listOverdueEnabled = { overdue },
        listEnabledRunning = { running },
        isRunOrphan = { runId -> runId in orphanRunIds },
        clearOrphanRunning = { id -> cleared += id },
        enqueue = { id, fireAt -> enqueued += id to fireAt },
        now = { 10_000L },
    )

    @Test
    fun `overdue enabled schedules are re-enqueued at their next fire time`() = runBlocking {
        val a = snapshot(nextFireAt = 2_000L)
        val b = snapshot(nextFireAt = 5_000L)
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val rescheduler = rescheduler(overdue = listOf(a, b), enqueued = enqueued)

        rescheduler.rescheduleOverdue()

        assertEquals(
            listOf(a.id to 2_000L, b.id to 5_000L),
            enqueued,
        )
    }

    @Test
    fun `an orphan running id is cleared before the schedule is re-enqueued`() = runBlocking {
        val orphanRun = Uuid.random()
        val pinned = snapshot(nextFireAt = 2_000L, runningTaskRunId = orphanRun)
        val cleared = mutableListOf<Uuid>()
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val rescheduler = rescheduler(
            overdue = listOf(pinned),
            orphanRunIds = setOf(orphanRun),
            cleared = cleared,
            enqueued = enqueued,
        )

        rescheduler.rescheduleOverdue()

        // The orphan marker is cleared so a future claim is not blocked by a dead fire...
        assertEquals(listOf(pinned.id), cleared)
        // ...and the schedule is still re-enqueued so it actually fires again.
        assertEquals(listOf(pinned.id to 2_000L), enqueued)
    }

    @Test
    fun `a live running id is NOT cleared`() = runBlocking {
        val liveRun = Uuid.random()
        val running = snapshot(nextFireAt = 2_000L, runningTaskRunId = liveRun)
        val cleared = mutableListOf<Uuid>()
        val rescheduler = rescheduler(
            overdue = listOf(running),
            orphanRunIds = emptySet(), // liveRun is not orphan: a real in-flight run owns it.
            cleared = cleared,
        )

        rescheduler.rescheduleOverdue()

        assertTrue("a live in-flight run must not have its marker cleared", cleared.isEmpty())
    }

    @Test
    fun `a recurring schedule claimed but not finished before process death is re-enqueued and un-pinned`() = runBlocking {
        // The process died after claimDue (which advanced nextFireAt to the FUTURE and set the
        // running marker) but before the worker re-enqueued the next fire. Such a row is NOT overdue,
        // so listOverdueEnabled never sees it — without the running-marker pass it would have no
        // pending WorkManager work AND a stale running marker, and the schedule would silently stop.
        val orphanRun = Uuid.random()
        val pinned = snapshot(nextFireAt = 50_000L, runningTaskRunId = orphanRun) // future nextFireAt
        val cleared = mutableListOf<Uuid>()
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val rescheduler = rescheduler(
            overdue = emptyList(),
            running = listOf(pinned),
            orphanRunIds = setOf(orphanRun),
            cleared = cleared,
            enqueued = enqueued,
        )

        rescheduler.rescheduleOverdue()

        // The orphan marker is cleared (its fire was killed) and the next future fire is re-armed.
        assertEquals(listOf(pinned.id), cleared)
        assertEquals(listOf(pinned.id to 50_000L), enqueued)
    }

    @Test
    fun `a schedule that is both overdue and running is reconciled exactly once`() = runBlocking {
        // A row can appear in both lists (overdue AND carrying a running marker). It must be cleared
        // and re-enqueued ONCE, never twice — duplicate enqueues are wasteful and a double clear is
        // pointless.
        val orphanRun = Uuid.random()
        val both = snapshot(nextFireAt = 2_000L, runningTaskRunId = orphanRun)
        val cleared = mutableListOf<Uuid>()
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val rescheduler = rescheduler(
            overdue = listOf(both),
            running = listOf(both),
            orphanRunIds = setOf(orphanRun),
            cleared = cleared,
            enqueued = enqueued,
        )

        rescheduler.rescheduleOverdue()

        assertEquals(listOf(both.id), cleared)
        assertEquals(listOf(both.id to 2_000L), enqueued)
    }
}
