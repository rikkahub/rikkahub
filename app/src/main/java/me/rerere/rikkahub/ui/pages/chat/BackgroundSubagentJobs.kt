package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag

/**
 * Project the live task-run rows of a conversation into the jobs sheet's [UiBackgroundJob] model,
 * keeping ONLY detached background runs still in flight (`is_background` + a non-terminal
 * [TaskRunStateTag]). A foreground run is awaited inline by its parent turn and never belongs in the
 * background sheet; a terminal run has already resolved its tool output via the completion drain.
 *
 * The "tail" of a subagent has no async output channel like a shell run, so the latest event summary
 * (or the prompt when none yet) is pre-rendered into [UiBackgroundJob.detail]; the flow re-emits on
 * every state transition, so this stays fresh without polling.
 */
internal fun mapBackgroundSubagentJobs(
    rows: List<TaskRunEntity>,
    conversationId: String,
): List<UiBackgroundJob> =
    rows.asSequence()
        .filter { row ->
            row.conversationId == conversationId &&
                row.isBackground &&
                TaskRunStateTag.fromPersistedOrNull(row.latestState)?.isTerminal == false
        }
        .sortedByDescending { it.createdAt }
        .map { row ->
            UiBackgroundJob(
                taskId = row.id,
                conversationId = row.conversationId,
                workspaceId = "",
                command = subagentJobCommand(row),
                status = subagentJobStatus(row.latestState),
                startedAt = row.createdAt,
                detachedAt = null,
                kind = BackgroundJobKind.Subagent,
                detail = subagentJobDetail(row),
                cancellable = true,
            )
        }
        .toList()

/** One-line label for the run: the agent type (if not the generic spawn alias) + the prompt. */
private fun subagentJobCommand(row: TaskRunEntity): String {
    val prompt = row.prompt.trim().ifBlank { "(no prompt)" }
    val type = row.agentTypeId.trim()
    return if (type.isBlank() || type == "agent" || type == "general") prompt else "$type: $prompt"
}

/** Friendly status from the persisted [TaskRunStateTag] name; unknown tags fall back to the raw name. */
private fun subagentJobStatus(latestState: String): String =
    when (TaskRunStateTag.fromPersistedOrNull(latestState)) {
        TaskRunStateTag.CREATED, TaskRunStateTag.QUEUED -> "Queued"
        TaskRunStateTag.STARTING -> "Starting"
        TaskRunStateTag.RUNNING -> "Running"
        TaskRunStateTag.WAITING_APPROVAL -> "Waiting approval"
        TaskRunStateTag.RESUMING -> "Resuming"
        else -> latestState
    }

/** The freshest progress line: the last event summary, or the prompt as the initial detail. */
private fun subagentJobDetail(row: TaskRunEntity): String {
    val lastSummary = row.decodeEventSummaries()?.lastOrNull()?.summary?.trim().orEmpty()
    return lastSummary.ifBlank { row.prompt.trim() }
}
