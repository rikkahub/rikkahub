package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.ScheduledTaskRunDAO
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity
import me.rerere.rikkahub.data.model.ScheduledTaskRun
import me.rerere.rikkahub.data.model.TaskRunStatus
import kotlin.uuid.Uuid

class ScheduledTaskRunRepository(
    private val dao: ScheduledTaskRunDAO
) {
    fun getRecentFinishedRuns(limit: Int = 200): Flow<List<ScheduledTaskRun>> {
        return dao.getRecentFinishedRuns(limit).map { list ->
            list.map { it.toModel() }
        }
    }

    fun getRunFlow(id: Uuid): Flow<ScheduledTaskRun?> {
        return dao.getRunFlow(id.toString()).map { it?.toModel() }
    }

    suspend fun getRunById(id: Uuid): ScheduledTaskRun? {
        return dao.getRunById(id.toString())?.toModel()
    }

    suspend fun deleteRun(id: Uuid) {
        dao.deleteRunById(id.toString())
    }

    suspend fun deleteAllFinishedRuns() {
        dao.deleteAllFinishedRuns()
    }

    suspend fun insertRun(run: ScheduledTaskRun) {
        dao.insert(run.toEntity())
    }

    suspend fun updateRun(run: ScheduledTaskRun) {
        dao.update(run.toEntity())
    }

    suspend fun pruneTaskRuns(taskId: Uuid, keep: Int) {
        dao.pruneTaskRuns(taskId.toString(), keep.coerceAtLeast(1))
    }
}

private fun ScheduledTaskRun.toEntity(): ScheduledTaskRunEntity {
    return ScheduledTaskRunEntity(
        id = id.toString(),
        taskId = taskId.toString(),
        taskTitleSnapshot = taskTitleSnapshot,
        assistantIdSnapshot = assistantIdSnapshot.toString(),
        status = status.name,
        startedAt = startedAt,
        finishedAt = finishedAt,
        durationMs = durationMs,
        promptSnapshot = promptSnapshot,
        resultText = resultText,
        errorText = errorText,
        modelIdSnapshot = modelIdSnapshot?.toString(),
        providerNameSnapshot = providerNameSnapshot,
    )
}

private fun ScheduledTaskRunEntity.toModel(): ScheduledTaskRun {
    return ScheduledTaskRun(
        id = Uuid.parse(id),
        taskId = Uuid.parse(taskId),
        taskTitleSnapshot = taskTitleSnapshot,
        assistantIdSnapshot = Uuid.parse(assistantIdSnapshot),
        status = runCatching { TaskRunStatus.valueOf(status) }.getOrDefault(TaskRunStatus.FAILED),
        startedAt = startedAt,
        finishedAt = finishedAt,
        durationMs = durationMs,
        promptSnapshot = promptSnapshot,
        resultText = resultText,
        errorText = errorText,
        modelIdSnapshot = modelIdSnapshot?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        providerNameSnapshot = providerNameSnapshot,
    )
}
