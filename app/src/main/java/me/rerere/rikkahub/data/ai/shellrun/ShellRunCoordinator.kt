package me.rerere.rikkahub.data.ai.shellrun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import me.rerere.workspace.ShellKillReason
import me.rerere.workspace.ShellRunHandle
import me.rerere.workspace.WorkspaceCommandResult
import kotlin.uuid.Uuid

/**
 * The spawn context the coordinator hands to [ShellRunCoordinator.startHandle] — everything
 * `WorkspaceManager.startShellRun` needs to spawn the process, plus the shell-specific detach policy.
 * [detachAfterSeconds] null means "never auto-detach" (the byte-compatible blocking path).
 */
data class ShellRunRequest(
    val workspaceId: String,
    /** The workspace files root the [startHandle] lambda passes to `WorkspaceManager.startShellRun`. */
    val root: String,
    val conversationId: Uuid,
    val command: String,
    val cwd: String?,
    /** The persisted workspace working-dir seed; resolution is override(cwd) > workingDir > files root. */
    val workingDir: String,
    val outputPath: String,
    val detachAfterSeconds: Int?,
    val hardTimeoutMillis: Long,
    val sizeCapBytes: Long,
)

/**
 * The result of [ShellRunCoordinator.run]. The shell tool maps each arm to the agent-facing JSON: an
 * [Inline] exit returns today's byte-compatible `{exitCode,stdout,stderr,timedOut,truncated}`; a
 * [Detached] run returns `{taskId,status:"running",outputRef,tail}` and the completion arrives later
 * as a synthetic #290 event.
 */
sealed interface ShellRunResult {
    /** The process exited before `detachAfterSeconds`: the inline result is already final. */
    data class Inline(val result: WorkspaceCommandResult) : ShellRunResult

    /**
     * `detachAfterSeconds` (or a user stop) won before exit: the run was backgrounded. The terminal
     * completion is delivered asynchronously via [ShellRunCoordinator.onCompletion].
     */
    data class Detached(
        val taskId: Uuid,
        val outputRef: String,
        val tail: String,
    ) : ShellRunResult
}

/**
 * The :app state machine for a `workspace_shell` run that may auto-background (issue #291):
 *
 * `STARTED -> FOREGROUND_WAITING -> {ExitedInline | Detached} -> BACKGROUND_RUNNING ->
 *  {SUCCEEDED | FAILED | KILLED_SIZE | KILLED_TIMEOUT | INTERRUPTED_PROCESS_DEATH}`.
 *
 * PURE / DIP: it takes the [ShellRunStore] seam, an [appScope] that outlives a turn, an
 * [onCompletion] sink (bound at the root to `ChatService.enqueueAgentEvent`), and a [startHandle]
 * lambda (bound to `WorkspaceManager.startShellRun`). So the whole state machine — including the
 * STOP_IS_DETACH_NOT_KILL and SINGLE_TERMINAL invariants — is JVM-unit-testable with a fake store and
 * a fake handle, mirroring `TaskCoordinator`.
 *
 * Detach decision (the design proposal's "one timer, two outcomes"): the foreground wait is bounded
 * by [ShellRunRequest.detachAfterSeconds]; on exit before that budget the run is [ShellRunResult.Inline],
 * otherwise it [ShellRunStore.detach]es and launches a detached awaiter. The hard timeout and size
 * cap are owned by the seam (the handle's `await()` enforces both and exposes
 * [ShellRunHandle.killReason]); the coordinator reads that reason to choose the terminal status. The
 * detach policy is a shell-specific [ShellRunRequest] field — it never touches `TaskBudget` (subagent
 * wall-time only).
 *
 * STOP_IS_DETACH_NOT_KILL: a user stop cancels the foreground-wait coroutine. The coordinator
 * catches that cancellation, detaches under [NonCancellable] (persist DETACHED + launch the awaiter
 * on [appScope], which outlives the turn), then RETHROWS the cancellation so the chat turn still
 * stops — it does NOT call `handle.await()` under interruption (that would `destroyForcibly` the
 * process, the very kill the design forbids). The turn finalizer separately leaves the backgrounded
 * tool part alone (no `{status:cancelled}`).
 */
class ShellRunCoordinator(
    private val store: ShellRunStore,
    private val appScope: CoroutineScope,
    private val onCompletion: suspend (ShellCompletion) -> Unit,
    private val startHandle: (ShellRunRequest) -> ShellRunHandle,
    /** Runs the blocking `handle.await()` off the calling coroutine; replaceable in tests. */
    private val awaitDispatcher: AwaitDispatcher = AwaitDispatcher.Default,
) {

    /**
     * Where the blocking [ShellRunHandle.await] runs. The default runs it on [Dispatchers.IO] (a
     * blocking, non-interruptible call), wrapped by the coordinator in a Deferred on [appScope] — so
     * the foreground wait can ABANDON the Deferred on the detach budget / a user stop WITHOUT killing
     * the process, and the SAME Deferred becomes the detached awaiter. Tests substitute a gate-driven
     * dispatcher so detach timing is deterministic.
     */
    fun interface AwaitDispatcher {
        suspend fun await(handle: ShellRunHandle): WorkspaceCommandResult

        companion object {
            val Default = AwaitDispatcher { handle ->
                withContext(kotlinx.coroutines.Dispatchers.IO) { handle.await() }
            }
        }
    }

    /**
     * Run the command. Returns [ShellRunResult.Inline] if it exits within
     * [ShellRunRequest.detachAfterSeconds], else detaches and returns [ShellRunResult.Detached].
     *
     * ONE Deferred owns the await for the WHOLE lifetime of the run. It is launched on [appScope]
     * (process-lifetime), so the turn's cancellation never reaches it — that is what backgrounds the
     * run on a Stop. The foreground caller only WAITS on that Deferred via [withTimeoutOrNull]; on the
     * detach budget OR a user stop it stops waiting and the Deferred continues as the detached awaiter,
     * terminalising the run + enqueuing the completion when the process finally exits. No second
     * await, no `withContext`-blocked-on-a-blocking-body hang, no respawn.
     *
     * @throws CancellationException on a user stop during the foreground wait — AFTER the run has been
     *   detached (the Deferred keeps running). The caller (the tool) then has no inline value; the
     *   completion arrives as a synthetic event.
     */
    suspend fun run(request: ShellRunRequest, taskId: Uuid = Uuid.random()): ShellRunResult {
        store.create(
            taskId = taskId,
            conversationId = request.conversationId,
            workspaceId = request.workspaceId,
            command = request.command,
            cwd = request.cwd.orEmpty(),
            outputPath = request.outputPath,
        )
        val handle = try {
            startHandle(request)
        } catch (t: Throwable) {
            // startHandle threw before the process started (blank command, bad cwd, etc.). The row
            // is already persisted as STARTED; mark it terminal so recovery does not later report it
            // as INTERRUPTED_PROCESS_DEATH (which would be a false completion for a process that
            // never existed).
            store.recordTerminal(taskId, ShellRunStatus.INTERRUPTED_PROCESS_DEATH, null, 0, null)
            throw t
        }
        // INVARIANT: once the process is live (startHandle returned) an awaiter MUST be installed, so
        // the run always reaches a terminal row + completion no matter how this turn ends. The awaiter
        // is created FIRST — appScope.async is non-suspending and cannot fail at creation — so the
        // invariant holds regardless of what markForegroundWaiting does. If the FOREGROUND_WAITING flip
        // then throws (a user Stop's CancellationException, OR a non-cancellation Room failure such as
        // a locked DB / disk-full), that throw propagates out of run() honestly, but the live process
        // is NOT stranded: the awaiter terminalises it via the store's CAS, which accepts a STARTED row
        // (and runningRows() includes STARTED), so the missed flip never breaks terminalisation.
        //
        // The single await Deferred, owned by appScope. On exit it ALWAYS terminalises the run via the
        // store's CAS, and the store reports — in the SAME transaction — whether the run had been
        // DETACHED (i.e. the agent already received a Detached handle and is owed a completion). That
        // atomic read is what kills the inline-vs-detach boundary race: the terminal write and the
        // "fire a completion?" decision are one transaction, so a process that exits exactly at the
        // detach budget can never both record an inline terminal AND return a Detached handle.
        val awaitJob = appScope.async {
            val result = awaitDispatcher.await(handle)
            recordTerminalAndMaybeNotify(taskId, request, handle, result)
            result
        }
        // markForegroundWaiting is the only cancellable suspend between the live process and the
        // foreground wait below; the awaiter is already installed above, so a Stop (or DB error) here
        // strands nothing. Run it NonCancellable so a user Stop does NOT abort the flip mid-write —
        // the held Stop then surfaces at the foreground-wait suspension below (where the catch detaches
        // it). A non-cancellation throw still propagates: the awaiter keeps the process honest.
        withContext(NonCancellable) {
            store.markForegroundWaiting(taskId, handle.pidMeta)
        }

        val detachBudgetMillis = request.detachAfterSeconds?.let { it.toLong() * 1_000L }

        val inline: WorkspaceCommandResult? = try {
            if (detachBudgetMillis == null) {
                // Never auto-detach: wait for the full result inline. The Deferred records the terminal
                // itself; await it so this call returns only once that terminal is persisted.
                awaitJob.await()
            } else {
                // Wait up to the detach budget. withTimeoutOrNull cancels only THIS wait (the Deferred
                // lives on appScope and keeps running); a null return means the budget elapsed first.
                withTimeoutOrNull(detachBudgetMillis) { awaitJob.await() }
            }
        } catch (cancellation: CancellationException) {
            // User stop during the foreground wait: detach (the Deferred keeps running on appScope),
            // then rethrow so the chat turn still stops. The persist runs NonCancellable so it survives
            // the in-flight cancellation. store.detach's CAS is the arbiter: if the process already
            // terminalised inline, detach matches zero rows and the run stays inline (no false Detached
            // handle is returned, because we rethrow rather than return here).
            withContext(NonCancellable) { detach(taskId, handle) }
            throw cancellation
        }

        if (inline != null) {
            // ExitedInline: the process produced a final result within the budget. The Deferred already
            // recorded the terminal; it was NOT detached, so no completion fired.
            return ShellRunResult.Inline(inline)
        }

        // The detach budget elapsed before exit. detach()'s CAS decides the truth: if it WON, the run
        // is now DETACHED and the agent gets a Detached handle (the Deferred will fire a completion on
        // exit). If it LOST, the process terminalised inline in the boundary window — fall back to the
        // already-persisted inline result so the agent is never told "running" for a finished run.
        if (detach(taskId, handle)) {
            return ShellRunResult.Detached(
                taskId = taskId,
                outputRef = request.outputPath,
                tail = handle.tail(DETACH_TAIL_BYTES),
            )
        }
        // Lost the detach CAS: the Deferred already produced the inline terminal — return it.
        return ShellRunResult.Inline(awaitJob.await())
    }

    /**
     * Flip the run to DETACHED. [store.detach] is the CAS gate: it returns true ONLY for the caller
     * that drove the real STARTED/FOREGROUND_WAITING -> DETACHED transition (a process that already
     * terminalised, or a second detach, matches zero rows and returns false). On a win it records
     * BACKGROUND_RUNNING. Returns whether this caller actually detached the run.
     */
    private suspend fun detach(taskId: Uuid, handle: ShellRunHandle): Boolean {
        val won = store.detach(taskId, handle.pidMeta)
        if (won) store.markBackgroundRunning(taskId)
        return won
    }

    /**
     * Record the terminal and, IFF the run had been DETACHED and this write won the CAS, enqueue
     * exactly one #290 completion event. Both facts come from the SAME store transaction
     * ([TerminalResult]), so AT_MOST_ONCE holds: a cold-start recovery scan racing the same row loses
     * the CAS, and an inline run reports wasDetached=false — neither fires a completion.
     */
    private suspend fun recordTerminalAndMaybeNotify(
        taskId: Uuid,
        request: ShellRunRequest,
        handle: ShellRunHandle,
        result: WorkspaceCommandResult,
    ) {
        val status = terminalStatusOf(result, handle.killReason)
        // The completion is built lazily inside the store transaction, and ONLY for a detached CAS
        // winner: building it calls handle.tail(), an uncaught file read, which must never run on the
        // inline path (an inline run must terminalise even if its tail read would have failed). The
        // store inserts the durable #290 event in the SAME transaction and hands the built row back.
        val terminal = store.recordTerminal(
            taskId = taskId,
            status = status,
            exitCode = result.exitCode,
            byteCount = handle.byteCount,
            killReason = handle.killReason?.name,
            buildCompletion = {
                ShellCompletion.of(
                    conversationId = request.conversationId,
                    taskId = taskId,
                    status = status,
                    exitCode = result.exitCode.takeIf { handle.killReason == null },
                    outputRef = request.outputPath,
                    tail = handle.tail(DETACH_TAIL_BYTES),
                    byteCount = handle.byteCount,
                )
            },
        )
        terminal.completion?.let { onCompletion(it) }
    }

    companion object {
        /** Bytes of output included inline in a detach/completion tail. */
        const val DETACH_TAIL_BYTES = 4 * 1024

        /**
         * Map a seam result + kill reason to the terminal [ShellRunStatus]. A kill reason is
         * authoritative (the seam tagged WHY it died); only a clean exit consults the exit code.
         */
        fun terminalStatusOf(result: WorkspaceCommandResult, killReason: ShellKillReason?): ShellRunStatus =
            when (killReason) {
                ShellKillReason.KilledSize -> ShellRunStatus.KILLED_SIZE
                ShellKillReason.KilledTimeout -> ShellRunStatus.KILLED_TIMEOUT
                // An explicit user kill is not a path the coordinator drives (a stop detaches, it does
                // not kill); treat a defensive KilledUser as FAILED rather than a fabricated success.
                ShellKillReason.KilledUser -> ShellRunStatus.FAILED
                null -> if (result.exitCode == 0) ShellRunStatus.SUCCEEDED else ShellRunStatus.FAILED
            }
    }
}
