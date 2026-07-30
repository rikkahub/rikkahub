package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentStepEntity
import me.rerere.rikkahub.data.db.entity.ToolExecutionEntity

@Dao
interface AgentRunDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStep(step: AgentStepEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertToolExecution(execution: ToolExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApproval(approval: AgentApprovalEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun getRun(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE parent_run_id = :parentRunId ORDER BY created_at ASC, id ASC")
    fun observeChildRuns(parentRunId: String): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs WHERE parent_run_id = :parentRunId ORDER BY created_at ASC, id ASC")
    suspend fun getChildRuns(parentRunId: String): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC")
    fun observeRunsForConversation(conversationId: String): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT 1")
    fun observeLatestRun(conversationId: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC")
    suspend fun getRunsForConversation(conversationId: String): List<AgentRunEntity>

    @Query(
        "SELECT * FROM agent_runs WHERE conversation_id = :conversationId " +
            "AND status IN (:statuses) ORDER BY updated_at DESC LIMIT 1"
    )
    suspend fun getActiveRun(conversationId: String, statuses: List<String>): AgentRunEntity?

    @Query(
        "SELECT * FROM agent_runs WHERE conversation_id = :conversationId " +
            "AND status IN (:statuses) ORDER BY updated_at DESC"
    )
    suspend fun getActiveRunsForConversation(conversationId: String, statuses: List<String>): List<AgentRunEntity>

    @Query(
        "SELECT * FROM agent_runs WHERE conversation_id = :conversationId " +
            "AND status IN (:statuses) ORDER BY updated_at DESC LIMIT 1"
    )
    fun observeActiveRun(conversationId: String, statuses: List<String>): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE status IN (:statuses) ORDER BY updated_at DESC")
    fun observeActiveRuns(statuses: List<String>): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs WHERE status IN (:statuses) ORDER BY updated_at DESC")
    suspend fun getActiveRuns(statuses: List<String>): List<AgentRunEntity>

    @Query("SELECT * FROM agent_steps WHERE run_id = :runId ORDER BY sequence ASC")
    suspend fun getSteps(runId: String): List<AgentStepEntity>

    @Query("SELECT * FROM agent_steps WHERE run_id = :runId ORDER BY sequence ASC")
    fun observeSteps(runId: String): Flow<List<AgentStepEntity>>

    @Query("SELECT * FROM agent_steps WHERE id = :id")
    suspend fun getStep(id: String): AgentStepEntity?

    @Query("SELECT COALESCE(MAX(sequence), -1) + 1 FROM agent_steps WHERE run_id = :runId")
    suspend fun nextStepSequence(runId: String): Int

    @Query("""
        UPDATE agent_steps
        SET status = :newStatus, summary_json = :summaryJson, updated_at = :updatedAt,
            finished_at = CASE WHEN :finishedAt IS NULL THEN finished_at ELSE :finishedAt END
        WHERE id = :id AND status IN (:expectedStatuses)
    """)
    suspend fun transitionStep(
        id: String,
        expectedStatuses: List<String>,
        newStatus: String,
        summaryJson: String?,
        updatedAt: Long,
        finishedAt: Long?,
    ): Int

    @Query("SELECT * FROM tool_executions WHERE run_id = :runId ORDER BY sequence ASC")
    suspend fun getToolExecutions(runId: String): List<ToolExecutionEntity>

    @Query("SELECT * FROM tool_executions WHERE run_id = :runId ORDER BY sequence ASC")
    fun observeToolExecutions(runId: String): Flow<List<ToolExecutionEntity>>

    @Query("SELECT * FROM tool_executions WHERE id = :id")
    suspend fun getToolExecution(id: String): ToolExecutionEntity?

    @Query("SELECT COALESCE(MAX(sequence), -1) + 1 FROM tool_executions WHERE run_id = :runId")
    suspend fun nextToolExecutionSequence(runId: String): Int

    @Query("""
        SELECT * FROM tool_executions
        WHERE run_id = :runId AND step_id = :stepId AND tool_name = :toolName
          AND tool_call_id = :toolCallId AND input_sha256 = :inputSha256
        LIMIT 1
    """)
    suspend fun getToolExecutionByIdentity(
        runId: String,
        stepId: String,
        toolName: String,
        toolCallId: String,
        inputSha256: String,
    ): ToolExecutionEntity?

    @Query("""
        SELECT * FROM tool_executions
        WHERE run_id = :runId AND step_id = :stepId AND tool_call_id = :toolCallId
        LIMIT 1
    """)
    suspend fun getToolExecutionByCallId(runId: String, stepId: String, toolCallId: String): ToolExecutionEntity?

    @Query("""
        SELECT * FROM tool_executions
        WHERE run_id = :runId AND tool_name = :toolName AND tool_call_id = :toolCallId
          AND input_sha256 = :inputSha256
        ORDER BY sequence DESC LIMIT 1
    """)
    suspend fun getLatestToolExecutionByCall(
        runId: String,
        toolName: String,
        toolCallId: String,
        inputSha256: String,
    ): ToolExecutionEntity?

    @Query("SELECT * FROM agent_approvals WHERE run_id = :runId ORDER BY sequence ASC")
    suspend fun getApprovals(runId: String): List<AgentApprovalEntity>

    @Query("SELECT * FROM agent_approvals WHERE run_id = :runId ORDER BY sequence ASC")
    fun observeApprovals(runId: String): Flow<List<AgentApprovalEntity>>

    @Query("SELECT * FROM agent_approvals WHERE id = :id")
    suspend fun getApproval(id: String): AgentApprovalEntity?

    @Query("""
        SELECT * FROM agent_approvals
        WHERE tool_execution_id = :toolExecutionId AND status = :status
        ORDER BY sequence DESC LIMIT 1
    """)
    suspend fun getApprovalForExecution(toolExecutionId: String, status: String): AgentApprovalEntity?

    @Query("""
        SELECT agent_approvals.* FROM agent_approvals
        INNER JOIN tool_executions ON tool_executions.id = agent_approvals.tool_execution_id
        WHERE agent_approvals.run_id = :runId AND agent_approvals.status = :approvalStatus
          AND tool_executions.status = :executionStatus AND tool_executions.tool_name = :toolName
          AND tool_executions.input_sha256 = :inputSha256
        ORDER BY agent_approvals.sequence ASC
    """)
    suspend fun getPendingApprovalsByToolIdentity(
        runId: String,
        toolName: String,
        inputSha256: String,
        approvalStatus: String,
        executionStatus: String,
    ): List<AgentApprovalEntity>

    @Query("SELECT COALESCE(MAX(sequence), -1) + 1 FROM agent_approvals WHERE run_id = :runId")
    suspend fun nextApprovalSequence(runId: String): Int

    @Query("""
        UPDATE agent_runs
        SET status = :newStatus, error_json = :errorJson, summary_json = COALESCE(:summaryJson, summary_json), updated_at = :updatedAt,
            started_at = CASE WHEN started_at IS NULL THEN :startedAt ELSE started_at END,
            finished_at = CASE WHEN :finishedAt IS NULL THEN finished_at ELSE :finishedAt END
        WHERE id = :id AND status IN (:expectedStatuses)
    """)
    suspend fun transitionRun(
        id: String,
        expectedStatuses: List<String>,
        newStatus: String,
        errorJson: String?,
        summaryJson: String?,
        updatedAt: Long,
        startedAt: Long?,
        finishedAt: Long?,
    ): Int

    @Query("UPDATE agent_runs SET summary_json = :summaryJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateRunSummary(id: String, summaryJson: String, updatedAt: Long): Int

    @Query("""
        UPDATE tool_executions
        SET status = :newStatus, error_json = :errorJson, summary_json = COALESCE(:summaryJson, summary_json), updated_at = :updatedAt,
            started_at = CASE WHEN started_at IS NULL THEN :startedAt ELSE started_at END,
            finished_at = CASE WHEN :finishedAt IS NULL THEN finished_at ELSE :finishedAt END
        WHERE id = :id AND status IN (:expectedStatuses)
    """)
    suspend fun transitionToolExecution(
        id: String,
        expectedStatuses: List<String>,
        newStatus: String,
        errorJson: String?,
        summaryJson: String?,
        updatedAt: Long,
        startedAt: Long?,
        finishedAt: Long?,
    ): Int

    @Query(
        "UPDATE agent_approvals SET status = :status, resolved_at = :resolvedAt " +
            "WHERE id = :id AND status = :expectedStatus"
    )
    suspend fun resolveApproval(id: String, expectedStatus: String, status: String, resolvedAt: Long): Int

    @Query("""
        UPDATE agent_steps SET status = :status, updated_at = :updatedAt, finished_at = :updatedAt
        WHERE run_id = :runId AND status IN (:activeStatuses)
    """)
    suspend fun terminateActiveSteps(
        runId: String,
        activeStatuses: List<String>,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE tool_executions SET status = :status, updated_at = :updatedAt, finished_at = :updatedAt
        WHERE run_id = :runId AND status IN (:activeStatuses)
    """)
    suspend fun terminateActiveToolExecutions(
        runId: String,
        activeStatuses: List<String>,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE agent_approvals SET status = :status, resolved_at = :resolvedAt
        WHERE run_id = :runId AND status = :pendingStatus
    """)
    suspend fun cancelPendingApprovals(runId: String, pendingStatus: String, status: String, resolvedAt: Long): Int

    @Query("""
        UPDATE agent_runs
        SET status = :interruptedStatus, error_json = :errorJson, updated_at = :updatedAt, finished_at = :updatedAt
        WHERE status IN (:activeStatuses)
    """)
    suspend fun interruptActiveRuns(
        activeStatuses: List<String>,
        interruptedStatus: String,
        errorJson: String,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE tool_executions
        SET status = :unknownStatus, updated_at = :updatedAt, finished_at = :updatedAt
        WHERE status IN (:activeStatuses)
    """)
    suspend fun interruptActiveToolExecutions(
        activeStatuses: List<String>,
        unknownStatus: String,
        updatedAt: Long,
    ): Int
}
