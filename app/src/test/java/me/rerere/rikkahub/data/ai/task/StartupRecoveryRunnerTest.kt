package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JVM unit tests for [StartupRecoveryRunner] — the single cold-start orchestrator
 * [me.rerere.rikkahub.RikkaHubApp] runs in ONE coroutine so the interrupt-scan finishes before the
 * schedule rescheduler reads run state (SPEC.md M6 / tasks T11 + SC#4).
 *
 * Root cause this pins: the two passes used to run in two independent `AppScope.launch` coroutines
 * with no happens-before edge. If rescheduling won the race, the orphan predicate (`isRunOrphan`)
 * read run state BEFORE recovery folded a killed run to `Interrupted`, so it returned false, left
 * the stale `running_task_run_id` marker in place, and `claimDue` then refused the schedule forever
 * — the recurring schedule was pinned "running" after a process kill. Ordering recovery strictly
 * before rescheduling removes the race.
 */
class StartupRecoveryRunnerTest {

    @Test
    fun `reschedule does not start until recovery completes`() = runBlocking {
        val order = CopyOnWriteArrayList<String>()
        val recoveryGate = CompletableDeferred<Unit>()
        val runner = StartupRecoveryRunner(
            recover = {
                order += "recover-start"
                recoveryGate.await() // hold recovery open so an unordered reschedule could interleave
                order += "recover-end"
            },
            reschedule = {
                order += "reschedule"
            },
        )

        val job = launch(Dispatchers.Default) { runner.run() }
        // Spin until recovery has entered and parked on the gate, giving an UNORDERED implementation
        // every chance to have already run reschedule. A correct sequential orchestrator must not.
        while ("recover-start" !in order) yield()
        repeat(100) { yield() }
        assertEquals(
            "reschedule must not start until recovery completes",
            listOf("recover-start"),
            order.toList(),
        )

        recoveryGate.complete(Unit)
        job.join()

        assertEquals(listOf("recover-start", "recover-end", "reschedule"), order.toList())
    }

    @Test
    fun `recovery runs strictly before rescheduling`() = runBlocking {
        val order = CopyOnWriteArrayList<String>()
        val runner = StartupRecoveryRunner(
            recover = { order += "recover" },
            reschedule = { order += "reschedule" },
        )

        runner.run()

        assertEquals(listOf("recover", "reschedule"), order.toList())
    }
}
