package me.rerere.rikkahub.data.ai.shellrun

import android.util.Log
import kotlin.uuid.Uuid

/**
 * The cold-start recovery pass for shell runs (issue #291), composed beside the agent-event +
 * task recovery runners and invoked once from `RikkaHubApp` startup.
 *
 * PROCESS_DEATH_IS_INTERRUPTED: a detached run survives only while the app PROCESS lives (the design
 * proposal's honest v1 — no dedicated shell foreground service). After a process kill, every row left
 * STARTED/FOREGROUND_WAITING/DETACHED/BACKGROUND_RUNNING is folded to INTERRUPTED_PROCESS_DEATH via
 * the store's terminal CAS — NEVER a fabricated SUCCEEDED. For each recovered row this MAY enqueue an
 * honest interrupted synthetic completion event ([enqueue]), so the agent learns the run was cut off;
 * dedupeKey = taskId keeps AT_MOST_ONCE — the recovery enqueue can never collide with a real
 * completion because the CAS guarantees only one terminal write per run.
 *
 * Failures are swallowed and logged so a recovery hiccup can never block the UI from coming up,
 * mirroring the agent-event/task startup-recovery posture. It NEVER claims completion after death.
 */
class ShellRunRecoveryRunner(
    private val store: ShellRunStore,
    private val enqueue: (conversationId: Uuid, kind: String, payloadJson: String, dedupeKey: String) -> Unit,
) {
    /**
     * Run the cold-start recovery once: fold every still-running row to INTERRUPTED_PROCESS_DEATH and
     * enqueue one honest interrupted event per recovered row. Returns the number of rows recovered
     * (for logging/tests).
     */
    suspend fun runStartupRecovery(): Int {
        val recovered = runCatching { store.recoverInterrupted() }
            .onFailure { Log.e(TAG, "shell-run recovery scan failed", it) }
            .getOrDefault(emptyList())
        if (recovered.isNotEmpty()) {
            Log.i(TAG, "shell-run recovery: ${recovered.size} interrupted run(s)")
        }
        recovered.forEach { row ->
            runCatching {
                val completion = ShellCompletion.of(
                    conversationId = Uuid.parse(row.conversationId),
                    taskId = Uuid.parse(row.taskId),
                    status = me.rerere.rikkahub.data.db.entity.ShellRunStatus.INTERRUPTED_PROCESS_DEATH,
                    exitCode = null,
                    outputRef = row.outputPath,
                    tail = "",
                    byteCount = row.byteCount,
                )
                enqueue(completion.conversationId, completion.kind, completion.payloadJson, completion.dedupeKey)
            }.onFailure { Log.e(TAG, "shell-run recovery enqueue failed for ${row.taskId}", it) }
        }
        return recovered.size
    }

    private companion object {
        const val TAG = "ShellRunRecovery"
    }
}
