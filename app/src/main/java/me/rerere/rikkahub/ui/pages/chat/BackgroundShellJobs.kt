package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.ShellRunStatus

private val BACKGROUND_JOB_STATUS_NAMES = setOf(
    ShellRunStatus.DETACHED.name,
    ShellRunStatus.BACKGROUND_RUNNING.name,
)

/** Which detached surface a [UiBackgroundJob] came from — they share the jobs sheet but differ in
 * how the tail is loaded and whether the user can cancel them. */
enum class BackgroundJobKind { Shell, Subagent }

data class UiBackgroundJob(
    val taskId: String,
    val conversationId: String,
    val workspaceId: String,
    val command: String,
    val status: String,
    val startedAt: Long?,
    val detachedAt: Long?,
    val kind: BackgroundJobKind = BackgroundJobKind.Shell,
    /** Pre-rendered tail for surfaces with no async output channel (subagent runs); shell uses the
     * async [ChatVM.tailBackgroundJob] path and leaves this null. */
    val detail: String? = null,
    /** True when the user can cancel this job from the sheet. Background shell runs stay view-only;
     * a background subagent is cancellable via its detached coordinator job. */
    val cancellable: Boolean = false,
)

internal fun mapBackgroundShellJobs(
    rows: List<ShellRunEntity>,
    conversationId: String,
): List<UiBackgroundJob> =
    rows.asSequence()
        .filter { it.conversationId == conversationId && it.status in BACKGROUND_JOB_STATUS_NAMES }
        .sortedByDescending { it.detachedAt ?: it.startedAt ?: it.createdAt }
        .map {
            UiBackgroundJob(
                taskId = it.taskId,
                conversationId = it.conversationId,
                workspaceId = it.workspaceId,
                command = it.command,
                status = it.status,
                startedAt = it.startedAt,
                detachedAt = it.detachedAt,
                kind = BackgroundJobKind.Shell,
                cancellable = false,
            )
        }
        .toList()

internal fun backgroundShellJobIndicatorCount(jobs: List<UiBackgroundJob>): Int = jobs.size

internal fun isBackgroundShellJobIndicatorVisible(jobs: List<UiBackgroundJob>): Boolean =
    backgroundShellJobIndicatorCount(jobs) > 0

internal fun UiBackgroundJob.elapsedStartMillis(): Long? = detachedAt ?: startedAt
