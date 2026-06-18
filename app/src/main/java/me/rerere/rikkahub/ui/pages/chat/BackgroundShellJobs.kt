package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.ShellRunStatus

private val BACKGROUND_JOB_STATUS_NAMES = setOf(
    ShellRunStatus.DETACHED.name,
    ShellRunStatus.BACKGROUND_RUNNING.name,
)

data class UiBackgroundJob(
    val taskId: String,
    val conversationId: String,
    val workspaceId: String,
    val command: String,
    val status: String,
    val startedAt: Long?,
    val detachedAt: Long?,
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
            )
        }
        .toList()

internal fun backgroundShellJobIndicatorCount(jobs: List<UiBackgroundJob>): Int = jobs.size

internal fun isBackgroundShellJobIndicatorVisible(jobs: List<UiBackgroundJob>): Boolean =
    backgroundShellJobIndicatorCount(jobs) > 0

internal fun UiBackgroundJob.elapsedStartMillis(): Long? = detachedAt ?: startedAt
