package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Single action in a workflow. Identical wire shape to scheduled-jobs direct-mode actions —
 * we reuse [me.rerere.rikkahub.service.DirectModeActionRunner] for execution.
 */
@Serializable
data class WorkflowAction(
    /** Tool name from the existing tool registry. Validation rejects unknown names. */
    val tool: String,
    /** JSON args matching what the LLM would emit in a chat tool call. */
    val args: JsonObject,
    /** Per-action timeout, 1..600s. Default 60. */
    val timeoutSeconds: Int = 60,
)

/**
 * Outcome of one workflow fire.
 *  - SUCCESS / FAILED — actually ran
 *  - SKIPPED_CONDITIONS — at least one condition evaluated false
 *  - SKIPPED_COOLDOWN — fired inside cooldown window
 *  - SKIPPED_DAILY_CAP — daily cap reached
 *  - SKIPPED_DISABLED — workflow toggle was off when trigger arrived (race-cleanup)
 */
enum class WorkflowRunStatus {
    SUCCESS,
    FAILED,
    SKIPPED_CONDITIONS,
    SKIPPED_COOLDOWN,
    SKIPPED_DAILY_CAP,
    SKIPPED_DISABLED,
}

/**
 * The full workflow definition the LLM authors. The server stores [definitionJson] in Room
 * as the source of truth and parses to this shape on every read; that way new fields can
 * be added without an Entity migration as long as defaults are sensible.
 */
@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: TriggerSpec,
    val conditions: List<ConditionSpec> = emptyList(),
    val actions: List<WorkflowAction>,
    /** Minimum gap between two consecutive fires in seconds. 0 = no cooldown. */
    val cooldownSeconds: Int = 0,
    /** Max successful+failed fires per local-day. null = unlimited. */
    val maxRunsPerDay: Int? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    /**
     * Stability fix (2026-05-07 audit) — UUID of the assistant that authored the workflow.
     * The engine resolves the runtime tool surface from THIS specific assistant at fire
     * time. If null (legacy rows pre-fix), the engine falls back to the previous
     * "any assistant with the Workflows toggle on" heuristic with a Log.w. New workflows
     * always have this set via the ToolInvocationContext propagation in workflow_create.
     */
    val authoringAssistantId: String? = null,
)

/**
 * One row of fire history.
 */
@Serializable
data class WorkflowRun(
    val rowId: Long,
    val workflowId: String,
    val firedAtMs: Long,
    val status: WorkflowRunStatus,
    val durationMs: Long,
    val errorMessage: String?,
)

object WorkflowConstants {
    const val MAX_NAME_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_ACTIONS = 32
    const val MIN_ACTION_TIMEOUT_S = 1
    const val MAX_ACTION_TIMEOUT_S = 600
    const val MAX_COOLDOWN_S = 24 * 60 * 60 // 24h
    const val MAX_RUNS_PER_DAY_FLOOR = 1
    const val MAX_RUNS_PER_DAY_CEIL = 1000
    const val MIN_GEOFENCE_RADIUS_M = 50
    const val MAX_GEOFENCE_RADIUS_M = 5000
    const val MAX_RUNS_HISTORY = 100
    const val MAX_ERROR_LENGTH = 500
}
