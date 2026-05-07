package me.rerere.rikkahub.workflow.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.model.WorkflowConstants
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 12 — single repository covering workflows + run history. The JSON blob in
 * [WorkflowEntity.definitionJson] is the source of truth — projected columns are derived.
 */
class WorkflowRepository(
    private val workflowDao: WorkflowDao,
    private val workflowRunDao: WorkflowRunDao,
) {

    data class Loaded(val entity: WorkflowEntity, val definition: WorkflowDefinition)

    fun observeAll(): Flow<List<Loaded>> = workflowDao.observeAll().map { rows ->
        rows.mapNotNull { row -> WorkflowJson.parseStored(row.definitionJson)?.let { Loaded(row, it) } }
    }

    fun observeById(id: String): Flow<Loaded?> = workflowDao.observeById(id).map { row ->
        row?.let {
            WorkflowJson.parseStored(it.definitionJson)?.let { def -> Loaded(it, def) }
        }
    }

    suspend fun listAll(): List<Loaded> =
        workflowDao.listAll().mapNotNull { row ->
            WorkflowJson.parseStored(row.definitionJson)?.let { Loaded(row, it) }
        }

    suspend fun listEnabled(): List<Loaded> =
        workflowDao.listEnabled().mapNotNull { row ->
            WorkflowJson.parseStored(row.definitionJson)?.let { Loaded(row, it) }
        }

    suspend fun getById(id: String): Loaded? = workflowDao.getById(id)?.let { row ->
        WorkflowJson.parseStored(row.definitionJson)?.let { Loaded(row, it) }
    }

    /** Insert or replace a workflow. Updates [definitionJson] from the canonical encoder. */
    suspend fun upsert(definition: WorkflowDefinition) {
        val entity = WorkflowEntity(
            id = definition.id,
            name = definition.name,
            description = definition.description,
            enabled = definition.enabled,
            definitionJson = WorkflowJson.encode(definition),
            createdAtMs = definition.createdAtMs,
            updatedAtMs = definition.updatedAtMs,
            // Preserve last-run state across upsert by reading the current row first; a fresh
            // create will simply find null and use defaults below.
        )
        val existing = workflowDao.getById(definition.id)
        val merged = if (existing != null) entity.copy(
            createdAtMs = existing.createdAtMs,    // creation time is immutable
            lastRunAtMs = existing.lastRunAtMs,
            lastRunStatus = existing.lastRunStatus,
            lastRunError = existing.lastRunError,
            runsTodayCount = existing.runsTodayCount,
            runsTodayDate = existing.runsTodayDate,
        ) else entity
        workflowDao.upsert(merged)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        workflowDao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    /** Delete the workflow row and its run history. */
    suspend fun deleteCascading(id: String): Boolean {
        workflowRunDao.deleteAllFor(id)
        return workflowDao.deleteById(id) > 0
    }

    /**
     * Record a fire — write a [WorkflowRunEntity] history row, update the projected
     * last-run columns + daily counter on the workflow, and trim history to
     * [WorkflowConstants.MAX_RUNS_HISTORY] rows.
     */
    suspend fun recordFire(
        workflowId: String,
        firedAtMs: Long,
        status: WorkflowRunStatus,
        durationMs: Long,
        errorMessage: String?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val truncatedErr = errorMessage?.take(WorkflowConstants.MAX_ERROR_LENGTH)
        workflowRunDao.insert(WorkflowRunEntity(
            workflowId = workflowId,
            firedAtMs = firedAtMs,
            status = status.name,
            durationMs = durationMs,
            errorMessage = truncatedErr,
        ))
        // Daily-cap counter: only counted if the fire was real (SUCCESS or FAILED). Skip
        // statuses don't count, per spec.
        val countsTowardCap = status == WorkflowRunStatus.SUCCESS || status == WorkflowRunStatus.FAILED
        val today = LocalDate.now(zoneId).toString()  // "yyyy-MM-dd"
        val current = workflowDao.getById(workflowId)
        val newCount = when {
            current == null -> if (countsTowardCap) 1 else 0
            current.runsTodayDate != today -> if (countsTowardCap) 1 else 0  // rolled over
            else -> current.runsTodayCount + (if (countsTowardCap) 1 else 0)
        }
        workflowDao.recordFire(
            id = workflowId,
            firedAtMs = firedAtMs,
            status = status.name,
            errorMessage = truncatedErr,
            runsTodayCount = newCount,
            runsTodayDate = today,
        )
        workflowRunDao.trim(workflowId, WorkflowConstants.MAX_RUNS_HISTORY)
    }

    suspend fun lastRuns(workflowId: String, limit: Int = 20): List<WorkflowRun> =
        workflowRunDao.lastN(workflowId, limit).map { row ->
            WorkflowRun(
                rowId = row.rowId,
                workflowId = row.workflowId,
                firedAtMs = row.firedAtMs,
                status = runCatching { WorkflowRunStatus.valueOf(row.status) }
                    .getOrDefault(WorkflowRunStatus.FAILED),
                durationMs = row.durationMs,
                errorMessage = row.errorMessage,
            )
        }
}
