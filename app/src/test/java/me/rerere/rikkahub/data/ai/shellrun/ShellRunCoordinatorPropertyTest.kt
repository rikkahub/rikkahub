package me.rerere.rikkahub.data.ai.shellrun

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import me.rerere.rikkahub.data.repository.BoardTransactionRunner
import me.rerere.rikkahub.data.repository.fakes.FakeShellRunDAO
import me.rerere.workspace.ShellKillReason
import me.rerere.workspace.ShellRunHandle
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Property suite for the shell-run coordinator + store (issue #291). Each property maps 1:1 to an
 * invariant in the maintainer design proposal and FAILS on a naive implementation for the exact
 * reason named in its KDoc. Everything runs on CI's JVM gate over a [FakeShellRunDAO]-backed
 * [RoomShellRunStore] and a programmable [FakeShellHandle] — no Room, no device, no PRoot rootfs.
 *
 * Detach timing is made deterministic WITHOUT virtual time by the handle's [FakeShellHandle.gate]: a
 * handle either resolves instantly (exits inline) or blocks on a gate the test releases (still
 * running), so the inline-vs-detach decision is separated by "returns now" vs "blocks", never by a
 * race between two close wall-clock values.
 */
class ShellRunCoordinatorPropertyTest {

    /** A serializing transaction runner — the same one-writer-at-a-time guarantee Room gives. */
    private class FakeTransactions : BoardTransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
    }

    /**
     * A programmable [ShellRunHandle]. [await] returns [result] either immediately (gate == null,
     * "exits inline") or after the test releases [gate] ("still running until released"). [killReason]
     * / [byteCount] / [tail] are fixed values the coordinator maps to a terminal status.
     */
    private class FakeShellHandle(
        private val result: WorkspaceCommandResult,
        override val killReason: ShellKillReason? = null,
        override val byteCount: Long = 0L,
        private val tailText: String = "",
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ShellRunHandle {
        val awaitCount = AtomicInteger(0)

        override fun await(): WorkspaceCommandResult {
            awaitCount.incrementAndGet()
            // Mirror the real handle: await blocks until the process "exits". The gate models that
            // exit. A null gate is an already-exited process (inline). Block on the gate from the
            // worker thread the AwaitDispatcher runs us on.
            gate?.let { runBlocking { it.await() } }
            return result
        }

        override fun kill(reason: ShellKillReason) = Unit
        override fun tail(maxBytes: Int): String = tailText
        override val pidMeta: String? = "pid=fake"
    }

    private fun store(dao: FakeShellRunDAO = FakeShellRunDAO()): RoomShellRunStore =
        RoomShellRunStore(dao = dao, transactions = FakeTransactions(), now = { clock++ })

    private var clock = 1L

    private fun ok(exit: Int = 0): WorkspaceCommandResult =
        WorkspaceCommandResult(exitCode = exit, stdout = "out", stderr = "", timedOut = false)

    private fun coordinator(
        store: ShellRunStore,
        appScope: CoroutineScope,
        startHandle: (ShellRunRequest) -> ShellRunHandle,
        onCompletion: suspend (ShellCompletion) -> Unit = {},
    ) = ShellRunCoordinator(
        store = store,
        appScope = appScope,
        onCompletion = onCompletion,
        startHandle = startHandle,
        // Mirror the production dispatcher: run the blocking await on IO (NOT NonCancellable) so a
        // withTimeoutOrNull detach budget and a Stop cancellation can abandon the *suspension* while
        // the gated worker keeps running — exactly the STOP_IS_DETACH_NOT_KILL seam behaviour.
        awaitDispatcher = ShellRunCoordinator.AwaitDispatcher {
            kotlinx.coroutines.withContext(Dispatchers.IO) { it.await() }
        },
    )

    private fun request(
        detachAfterSeconds: Int?,
        conversationId: Uuid = Uuid.random(),
    ) = ShellRunRequest(
        workspaceId = "ws",
        root = "ws",
        conversationId = conversationId,
        command = "echo hi",
        cwd = null,
        workingDir = "",
        outputPath = "/tmp/$conversationId.output",
        detachAfterSeconds = detachAfterSeconds,
        hardTimeoutMillis = 30_000,
        sizeCapBytes = 8L * 1024 * 1024,
    )

    // --- SINGLE_TERMINAL --------------------------------------------------------------------------
    // Two concurrent recordTerminal writes on the same run -> exactly ONE Won, and the store ends in
    // exactly one terminal status. Fails-before if the store did a read-then-write instead of the
    // conditional CAS: both reads would see a running row and both would write a terminal.
    @Test
    fun `single terminal across concurrent terminal writes`(): Unit = runBlocking {
        checkAll(50, Arb.int(0..1)) { exitSel ->
            val dao = FakeShellRunDAO()
            val st = store(dao)
            val taskId = Uuid.random()
            st.create(taskId, Uuid.random(), "ws", "cmd", "", "/tmp/x")
            val statusA = ShellRunStatus.SUCCEEDED
            val statusB = if (exitSel == 0) ShellRunStatus.FAILED else ShellRunStatus.KILLED_TIMEOUT

            val a = async(Dispatchers.Default) {
                st.recordTerminal(taskId, statusA, 0, 0, null)
            }
            val b = async(Dispatchers.Default) {
                st.recordTerminal(taskId, statusB, 1, 0, ShellKillReason.KilledTimeout.name)
            }
            val outcomes = listOf(a.await(), b.await())

            assertEquals(
                "exactly one terminal write wins the CAS",
                1,
                outcomes.count { it.outcome == TerminalOutcome.Won },
            )
            val finalStatus = ShellRunStatus.fromPersistedOrNull(dao.getById(taskId.toString())!!.status)
            assertNotNull(finalStatus)
            assertTrue("the store ends in a terminal status", finalStatus!!.isTerminal)
        }
    }

    // --- STOP_IS_DETACH_NOT_KILL ------------------------------------------------------------------
    // A user stop during the foreground wait DETACHES the run (persists DETACHED + leaves a running
    // awaiter) and does NOT kill it; it rethrows cancellation so the turn still stops. Fails-before if
    // the stop reached the seam's await->destroyForcibly (the run would be killed, not backgrounded).
    @Test
    fun `stop during foreground wait detaches and does not kill`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val st = store(dao)
        val appScope = CoroutineScope(Job() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>() // never released by the stop -> still running
        val handle = FakeShellHandle(result = ok(0), gate = gate)
        val coord = coordinator(st, appScope, startHandle = { handle })
        val taskId = Uuid.random()

        // Run the coordinator in a child job we can cancel (the "Stop").
        val runJob = launch(Dispatchers.Default) {
            // detachAfter is large so the foreground wait is in progress when we cancel; the cancel —
            // not the budget — drives the detach.
            coord.run(request(detachAfterSeconds = 100), taskId)
        }
        // Wait until the foreground await is in flight, then stop.
        withTimeout(2_000) { while (handle.awaitCount.get() == 0) delay(5) }
        runJob.cancelAndJoin()

        // The run is DETACHED (persisted under NonCancellable), NOT killed: the handle was never
        // kill()ed and the row is in a running/detached state with a live awaiter.
        withTimeout(2_000) {
            while (dao.getById(taskId.toString())?.status == ShellRunStatus.FOREGROUND_WAITING.name) delay(5)
        }
        val row = dao.getById(taskId.toString())!!
        assertTrue(
            "a stopped foreground run is detached/background-running, not terminal",
            row.status == ShellRunStatus.DETACHED.name || row.status == ShellRunStatus.BACKGROUND_RUNNING.name,
        )
        assertNull("the process was not killed on stop", row.killReason)

        // Now let the still-running process exit; the detached awaiter terminalises it as SUCCEEDED.
        gate.complete(Unit)
        withTimeout(2_000) {
            while (dao.getById(taskId.toString())?.status != ShellRunStatus.SUCCEEDED.name) delay(5)
        }
        appScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // --- STOP_IS_DETACH_NOT_KILL: the PRE-AWAITER gap ---------------------------------------------
    // The `stop during foreground wait` test above only cancels AFTER awaitCount > 0 — i.e. AFTER the
    // appScope awaiter is already installed. This test pins the invariant that the awaiter is installed
    // BEFORE the FOREGROUND_WAITING flip can fail: the process is ALREADY LIVE (startHandle returned)
    // and a Stop lands exactly on store.markForegroundWaiting. The fix creates the appScope awaiter
    // FIRST (a non-suspending, never-failing async) and only THEN flips FOREGROUND_WAITING, so the
    // awaiter is guaranteed installed once the process is live; the Stop then surfaces at the foreground
    // wait. Fails-before any ordering where the flip precedes the awaiter (a throw there strands).
    @Test
    fun `stop during pre-awaiter foreground-waiting transition still installs the awaiter`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        // A store decorator that suspends INSIDE markForegroundWaiting (the pre-awaiter cancellable
        // suspend), so a Stop can be delivered exactly there before the appScope awaiter is created.
        val entered = CompletableDeferred<Unit>()
        val markGate = CompletableDeferred<Unit>()
        val inner = store(dao)
        val st = object : ShellRunStore by inner {
            override suspend fun markForegroundWaiting(taskId: Uuid, pidMeta: String?) {
                entered.complete(Unit)
                markGate.await() // hold the foreground-waiting transition open across the Stop
                inner.markForegroundWaiting(taskId, pidMeta)
            }
        }
        val appScope = CoroutineScope(Job() + Dispatchers.Default)
        val processGate = CompletableDeferred<Unit>() // process stays alive until released
        val handle = FakeShellHandle(result = ok(0), gate = processGate)
        val coord = coordinator(st, appScope, startHandle = { handle })
        val taskId = Uuid.random()

        // Run the coordinator in a child job; the process is started before markForegroundWaiting, so
        // when we cancel below the Stop lands on the markForegroundWaiting suspend — the pre-awaiter gap.
        val runJob = launch(Dispatchers.Default) {
            coord.run(request(detachAfterSeconds = 100), taskId)
        }
        withTimeout(2_000) { entered.await() }
        runJob.cancel()
        // Release the held transition; under the fix it runs NonCancellable and the awaiter is created.
        markGate.complete(Unit)
        runJob.join()

        // The appScope awaiter WAS installed despite the Stop landing on the markForegroundWaiting
        // suspend — fails-before because unfixed code throws out before appScope.async runs (count 0).
        withTimeout(2_000) { while (handle.awaitCount.get() == 0) delay(5) }
        assertTrue("the detached awaiter is installed even when the Stop hits the pre-awaiter gap", handle.awaitCount.get() > 0)

        // Let the live process exit; the installed awaiter terminalises the row instead of stranding it.
        processGate.complete(Unit)
        withTimeout(2_000) {
            while (dao.getById(taskId.toString())?.status != ShellRunStatus.SUCCEEDED.name) delay(5)
        }
        appScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // --- STOP_IS_DETACH_NOT_KILL: the FOREGROUND_WAITING flip can FAIL (non-cancellation) ----------
    // The pre-awaiter gap is not only reachable by a user Stop. markForegroundWaiting is a suspending
    // Room transaction; a NON-cancellation throw (SQLiteException, DB locked, disk full) can propagate
    // out of it for a perfectly live process. If the FOREGROUND_WAITING flip ran BEFORE the awaiter was
    // created, that throw would escape run() with the live process holding NO awaiter — never
    // terminalised (stranded), no completion event — the exact invariant the fix closes, just triggered
    // by a DB error rather than a Stop. The catch in run() only handles CancellationException, so a
    // non-cancellation throw is NOT caught; it propagates honestly, but the awaiter (created FIRST) must
    // already exist so the row still terminalises on process exit. Fails-before any ordering where the
    // flip precedes the awaiter. (markTerminalIfRunning's CAS accepts a STARTED row and runningRows()
    // includes STARTED, so the awaiter terminalises correctly even though the flip never landed.)
    @Test
    fun `non-cancellation failure of foreground-waiting flip still terminalises via the awaiter`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        // A store decorator whose markForegroundWaiting throws a non-cancellation error (a DB failure),
        // never reaching the real flip — so the row stays STARTED.
        val inner = store(dao)
        val st = object : ShellRunStore by inner {
            override suspend fun markForegroundWaiting(taskId: Uuid, pidMeta: String?) {
                throw IllegalStateException("simulated DB failure (locked / disk full)")
            }
        }
        val appScope = CoroutineScope(Job() + Dispatchers.Default)
        val processGate = CompletableDeferred<Unit>() // process stays alive until released
        val handle = FakeShellHandle(result = ok(0), gate = processGate)
        val coord = coordinator(st, appScope, startHandle = { handle })
        val taskId = Uuid.random()

        // run() must propagate the non-cancellation throw (it is NOT a swallowed error) — assert it.
        var thrown: Throwable? = null
        val runJob = launch(Dispatchers.Default) {
            try {
                coord.run(request(detachAfterSeconds = 100), taskId)
            } catch (t: IllegalStateException) {
                thrown = t
            }
        }
        runJob.join()
        assertNotNull("the non-cancellation DB failure propagates out of run()", thrown)

        // The awaiter WAS installed before the failing flip — the live process is not stranded. Let it
        // exit; the awaiter terminalises the STARTED row to SUCCEEDED via the CAS instead of leaking it.
        withTimeout(2_000) { while (handle.awaitCount.get() == 0) delay(5) }
        processGate.complete(Unit)
        withTimeout(2_000) {
            while (dao.getById(taskId.toString())?.status != ShellRunStatus.SUCCEEDED.name) delay(5)
        }
        assertEquals(ShellRunStatus.SUCCEEDED.name, dao.getById(taskId.toString())!!.status)
        appScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // --- STOP_IS_DETACH: hard-timeout / size-cap DO kill -----------------------------------------
    // The coordinator does not invent a kill on stop, but the SEAM's hard-timeout / size-cap kills
    // still map to terminal KILLED_TIMEOUT / KILLED_SIZE when the awaiter observes them.
    @Test
    fun `hard timeout and size cap map to killed terminals`(): Unit = runBlocking {
        val timeoutStatus = ShellRunCoordinator.terminalStatusOf(
            WorkspaceCommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true),
            ShellKillReason.KilledTimeout,
        )
        assertEquals(ShellRunStatus.KILLED_TIMEOUT, timeoutStatus)
        val sizeStatus = ShellRunCoordinator.terminalStatusOf(
            WorkspaceCommandResult(exitCode = -1, stdout = "", stderr = ""),
            ShellKillReason.KilledSize,
        )
        assertEquals(ShellRunStatus.KILLED_SIZE, sizeStatus)
    }

    // --- METAMORPHIC detach -----------------------------------------------------------------------
    // A process that exits at "D": detachAfter < D -> Detached then SUCCEEDED; detachAfter unset (>D)
    // -> ExitedInline. The final exit code / output are IDENTICAL across both — only the inline-vs-
    // background shape differs. Fails-before if detach changed the terminal result (it must not).
    @Test
    fun `metamorphic detach changes only inline vs background`(): Unit = runBlocking {
        val exitCode = 0
        val output = ok(exitCode)

        // Branch A: never auto-detach (null budget) -> the handle exits inline.
        run {
            val dao = FakeShellRunDAO()
            val st = store(dao)
            val appScope = CoroutineScope(Job() + Dispatchers.Default)
            val handle = FakeShellHandle(result = output, byteCount = 7, tailText = "tail")
            val coord = coordinator(st, appScope, startHandle = { handle })
            val taskId = Uuid.random()
            val result = coord.run(request(detachAfterSeconds = null), taskId)
            assertTrue("no budget -> inline", result is ShellRunResult.Inline)
            assertEquals(exitCode, (result as ShellRunResult.Inline).result.exitCode)
            assertEquals(ShellRunStatus.SUCCEEDED.name, dao.getById(taskId.toString())!!.status)
            assertEquals(exitCode, dao.getById(taskId.toString())!!.exitCode)
            appScope.coroutineContext[Job]!!.cancelAndJoin()
        }

        // Branch B: detach before exit (budget tiny, process gated open) -> Detached then SUCCEEDED.
        run {
            val dao = FakeShellRunDAO()
            val st = store(dao)
            val appScope = CoroutineScope(Job() + Dispatchers.Default)
            val gate = CompletableDeferred<Unit>()
            val handle = FakeShellHandle(result = output, byteCount = 7, tailText = "tail", gate = gate)
            val completionSignal = CompletableDeferred<ShellCompletion>()
            val coord = coordinator(
                st, appScope,
                startHandle = { handle },
                onCompletion = { completionSignal.complete(it) },
            )
            val taskId = Uuid.random()
            // 1s budget; the gated handle never returns within it, so the run detaches.
            val result = coord.run(request(detachAfterSeconds = 1), taskId)
            assertTrue("budget < exit -> detached", result is ShellRunResult.Detached)
            // The process exits now; the detached awaiter terminalises identically to branch A and
            // delivers the completion — await it deterministically.
            gate.complete(Unit)
            val completion = withTimeout(2_000) { completionSignal.await() }
            val row = dao.getById(taskId.toString())!!
            assertEquals("final exit code identical to inline branch", exitCode, row.exitCode)
            assertEquals(ShellRunStatus.SUCCEEDED.name, row.status)
            assertEquals("a detached completion event was enqueued", taskId.toString(), completion.dedupeKey)
            appScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    // --- NO_PROCESS_WHEN_DISABLED -----------------------------------------------------------------
    // (Coordinator layer) When the workspace is disabled the repository short-circuits BEFORE the
    // coordinator runs, so startHandle is never invoked. Modelled here at the coordinator boundary:
    // the repository guard is exercised in WorkspaceRepositoryShellEnableTest; this asserts the
    // coordinator never spawns a second handle once it has one (no respawn on detach).
    @Test
    fun `detach does not respawn a second handle`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val st = store(dao)
        val appScope = CoroutineScope(Job() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val handle = FakeShellHandle(result = ok(0), gate = gate)
        val startCount = AtomicInteger(0)
        val coord = coordinator(st, appScope, startHandle = {
            startCount.incrementAndGet(); handle
        })
        val taskId = Uuid.random()
        val result = coord.run(request(detachAfterSeconds = 1), taskId)
        assertTrue(result is ShellRunResult.Detached)
        gate.complete(Unit)
        withTimeout(2_000) {
            while (dao.getById(taskId.toString())?.status != ShellRunStatus.SUCCEEDED.name) delay(5)
        }
        assertEquals("the process is started exactly once across detach", 1, startCount.get())
        appScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // --- OUTPUT_CAP_KILLS -------------------------------------------------------------------------
    // A handle whose killReason is KilledSize maps to a terminal KILLED_SIZE (and the completion
    // carries no exitCode, since a killed run has no clean exit).
    @Test
    fun `size cap kill maps to KILLED_SIZE terminal`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val st = store(dao)
        val appScope = CoroutineScope(Job() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val handle = FakeShellHandle(
            result = WorkspaceCommandResult(exitCode = -1, stdout = "", stderr = ""),
            killReason = ShellKillReason.KilledSize,
            byteCount = 1_000_000,
            gate = gate,
        )
        // The completion is delivered on the appScope awaiter; await it deterministically rather than
        // racing a status poll against the onCompletion assignment.
        val completionSignal = CompletableDeferred<ShellCompletion>()
        val coord = coordinator(st, appScope, startHandle = { handle }, onCompletion = { completionSignal.complete(it) })
        val taskId = Uuid.random()
        coord.run(request(detachAfterSeconds = 1), taskId)
        gate.complete(Unit)
        val completion = withTimeout(2_000) { completionSignal.await() }
        assertEquals(ShellRunStatus.KILLED_SIZE.name, dao.getById(taskId.toString())!!.status)
        assertFalse(
            "a size-killed completion carries no clean exit code",
            completion.payloadJson.contains("\"exitCode\""),
        )
        appScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // --- PROCESS_DEATH -> INTERRUPTED, and a post-death completion is rejected ---------------------
    // recoverInterrupted folds every running row to INTERRUPTED_PROCESS_DEATH (never SUCCEEDED), and a
    // LATER terminal write (a stale awaiter that survived) is rejected by the CAS (Lost). Fails-before
    // if recovery fabricated a success, or if a post-death write could overwrite the interrupted tag.
    @Test
    fun `process death recovery interrupts and rejects a later completion`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val st = store(dao)
        val taskId = Uuid.random()
        st.create(taskId, Uuid.random(), "ws", "cmd", "", "/tmp/x")
        st.markForegroundWaiting(taskId, "pid")
        st.detach(taskId, "pid")
        st.markBackgroundRunning(taskId)

        val recovered = st.recoverInterrupted()
        assertEquals("the running row was recovered", 1, recovered.size)
        assertEquals(
            ShellRunStatus.INTERRUPTED_PROCESS_DEATH.name,
            dao.getById(taskId.toString())!!.status,
        )

        // A later "completion" from a stale awaiter must LOSE the CAS — never claims success.
        val late = st.recordTerminal(taskId, ShellRunStatus.SUCCEEDED, 0, 0, null)
        assertEquals(TerminalOutcome.Lost, late.outcome)
        assertEquals(
            "the interrupted tag is never overwritten by a post-death completion",
            ShellRunStatus.INTERRUPTED_PROCESS_DEATH.name,
            dao.getById(taskId.toString())!!.status,
        )
    }

    // --- AT_MOST_ONCE completion: a Won terminal fires exactly one completion; a Lost fires none ----
    @Test
    fun `only the winning terminal fires a completion`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val st = store(dao)
        val taskId = Uuid.random()
        val conversationId = Uuid.random()
        st.create(taskId, conversationId, "ws", "cmd", "", "/tmp/x")
        st.detach(taskId, "pid")

        val deliveries = mutableListOf<String>()
        val onCompletion: suspend (ShellCompletion) -> Unit = { deliveries += it.dedupeKey }

        // First terminal wins on a DETACHED row -> fires a completion (wasDetached holds).
        val first = st.recordTerminal(taskId, ShellRunStatus.SUCCEEDED, 0, 0, null)
        if (first.outcome == TerminalOutcome.Won && first.wasDetached) {
            onCompletion(ShellCompletion.of(conversationId, taskId, ShellRunStatus.SUCCEEDED, 0, "/tmp/x", "", 0))
        }
        // Second terminal loses -> fires nothing.
        val second = st.recordTerminal(taskId, ShellRunStatus.FAILED, 1, 0, null)
        if (second.outcome == TerminalOutcome.Won && second.wasDetached) {
            onCompletion(ShellCompletion.of(conversationId, taskId, ShellRunStatus.FAILED, 1, "/tmp/x", "", 0))
        }

        assertEquals(TerminalOutcome.Won, first.outcome)
        assertTrue("the winning terminal saw a detached row", first.wasDetached)
        assertEquals(TerminalOutcome.Lost, second.outcome)
        assertEquals("exactly one completion delivered", 1, deliveries.size)
        assertEquals(taskId.toString(), deliveries.single())
    }

    // --- CWD persisted distinctness: an inline exit never enqueues a completion --------------------
    @Test
    fun `inline exit does not enqueue a completion`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val st = store(dao)
        val appScope = CoroutineScope(Job() + Dispatchers.Default)
        val handle = FakeShellHandle(result = ok(0))
        var completion: ShellCompletion? = null
        val coord = coordinator(st, appScope, startHandle = { handle }, onCompletion = { completion = it })
        val taskId = Uuid.random()
        val result = coord.run(request(detachAfterSeconds = null), taskId)
        assertTrue(result is ShellRunResult.Inline)
        assertNull("an inline exit returns to the tool inline; no synthetic event", completion)
        appScope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
