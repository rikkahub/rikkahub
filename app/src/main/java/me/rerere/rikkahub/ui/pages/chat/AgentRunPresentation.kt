package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentStepEntity
import me.rerere.rikkahub.data.db.entity.ToolExecutionEntity
import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.model.AgentApprovalStatus
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunError
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentRunSummary
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import me.rerere.rikkahub.data.model.ToolExecutionSummary
import me.rerere.rikkahub.utils.JsonInstant

data class AgentRunDetail(
    val run: AgentRunEntity,
    val steps: List<AgentStepEntity>,
    val tools: List<ToolExecutionEntity>,
    val approvals: List<AgentApprovalEntity>,
    val children: List<AgentRunEntity> = emptyList(),
    val traceEvents: List<AgentTraceEvent> = emptyList(),
)

data class ChildRunPresentation(
    val runId: String,
    val status: String,
    val findings: String,
)

data class AgentRunPresentation(
    val runId: String,
    val status: String,
    val statusDescription: String?,
    val model: String?,
    val mode: String?,
    val runtimeVersion: String?,
    val maxSteps: Int?,
    val completedSteps: Int,
    val currentStep: String?,
    val waitingReason: String?,
    val createdAt: Long,
    val duration: String,
    val failureCategory: String?,
    val timeline: List<AgentRunTimelineItem>,
    val children: List<ChildRunPresentation> = emptyList(),
)

data class AgentRunTimelineItem(
    val sequence: Int,
    val label: String,
    val status: String,
    val duration: String,
    val summary: String?,
    val outputSummary: String?,
    val failureCategory: String?,
    val approval: String?,
    val approvalReason: String?,
    val timestampMillis: Long = 0,
)

fun AgentRunEntity.toPresentation(): AgentRunPresentation = AgentRunDetail(this, emptyList(), emptyList(), emptyList()).toPresentation()

fun AgentRunDetail.toPresentation(): AgentRunPresentation {
    val config = run.configSnapshotJson.decodeOrNull<AgentRunConfigSnapshot>()
    val runSummary = run.summaryJson?.decodeOrNull<AgentRunSummary>()
    val runError = run.errorJson?.decodeOrNull<AgentRunError>()
    val activeStep = steps.lastOrNull { it.status == AgentStepStatus.RUNNING.name } ?: steps.lastOrNull()
    val completedSteps = runSummary?.completedSteps ?: steps.count { it.status == AgentStepStatus.SUCCEEDED.name }
    val pendingApproval = approvals.firstOrNull { it.status == AgentApprovalStatus.PENDING.name }
    val waitingReason = pendingApproval?.summaryJson?.decodeOrNull<AgentApprovalSummary>()?.reasonCode.userFacingApprovalReason()
        ?: if (run.status == AgentRunStatus.WAITING_APPROVAL.name) "等待聊天中的工具审批" else null

    return AgentRunPresentation(
        runId = run.id,
        status = run.status.statusLabel(),
        statusDescription = run.status.statusDescription(),
        model = config?.modelId,
        mode = config?.agentMode,
        runtimeVersion = config?.runtimeVersion,
        maxSteps = config?.maxSteps,
        completedSteps = completedSteps,
        currentStep = activeStep?.let { "步骤 ${it.sequence + 1} · ${it.kind}" },
        waitingReason = waitingReason,
        createdAt = run.createdAt,
        duration = durationLabel(run.startedAt ?: run.createdAt, run.finishedAt ?: run.updatedAt),
        failureCategory = runError.userFacingFailure(),
        timeline = timelineItems(),
        children = children.map { child ->
            val report = child.summaryJson?.decodeOrNull<AgentRunSummary>()?.childReport
            ChildRunPresentation(
                runId = child.id,
                status = child.status.statusLabel(),
                findings = report?.findings?.joinToString(" ")?.take(240).orEmpty(),
            )
        },
    )
}

private fun AgentRunDetail.timelineItems(): List<AgentRunTimelineItem> {
    val approvalsByTool = approvals.associateBy { it.toolExecutionId }
    val toolsByStep = HashMap<String, MutableList<ToolExecutionEntity>>()
    val orphanTools = ArrayList<ToolExecutionEntity>()
    val stepIds = steps.mapTo(hashSetOf()) { it.id }
    // DAO queries tools by sequence. Preserve that stable order while partitioning in one pass.
    tools.forEach { tool ->
        if (tool.stepId in stepIds) {
            toolsByStep.getOrPut(tool.stepId, ::ArrayList).add(tool)
        } else {
            orphanTools += tool
        }
    }
    return buildList {
        steps.forEach { step ->
            add(
                AgentRunTimelineItem(
                    sequence = step.sequence * 10,
                    label = "步骤 ${step.sequence + 1} · ${step.kind}",
                    status = step.status.statusLabel(),
                    duration = durationLabel(step.createdAt, step.finishedAt ?: step.updatedAt),
                    summary = null,
                    outputSummary = null,
                    failureCategory = null,
                    approval = null,
                    approvalReason = null,
                    timestampMillis = step.createdAt,
                )
            )
            toolsByStep[step.id].orEmpty().forEach { tool -> add(tool.toTimelineItem(approvalsByTool[tool.id])) }
        }
        orphanTools.forEach { tool ->
            add(tool.toTimelineItem(approvalsByTool[tool.id]))
        }
        traceEvents.forEach { event -> add(event.toTimelineItem()) }
    }.sortedBy { it.timestampMillis }
}

private fun ToolExecutionEntity.toTimelineItem(approval: AgentApprovalEntity?): AgentRunTimelineItem {
    val summary = summaryJson?.decodeOrNull<ToolExecutionSummary>()
        ?.let { listOfNotNull(it.category, it.operation, it.targetType).joinToString(" / ") }
        ?.ifBlank { null }
    val outputSummary = summaryJson?.decodeOrNull<ToolExecutionSummary>()?.outputBytes
        ?.let { "输出已脱敏（$it B）" }
    val error = errorJson?.decodeOrNull<AgentRunError>()
    val approvalSummary = approval?.summaryJson?.decodeOrNull<AgentApprovalSummary>()
    return AgentRunTimelineItem(
        sequence = sequence * 10 + 1,
        label = "工具 · $toolName",
        status = status.statusLabel(),
        duration = durationLabel(startedAt ?: createdAt, finishedAt ?: updatedAt),
        summary = summary,
        outputSummary = outputSummary,
        failureCategory = error.userFacingFailure(),
        approval = approval?.status?.statusLabel(),
        approvalReason = approvalSummary?.reasonCode.userFacingApprovalReason(),
        timestampMillis = createdAt,
    )
}

private fun AgentTraceEvent.toTimelineItem(): AgentRunTimelineItem = AgentRunTimelineItem(
    sequence = 1_000_000 + sequence,
    label = "追踪 · ${type.replace('_', ' ')}",
    status = status.statusLabel(),
    duration = durationLabel(0, durationMillis ?: 0),
    summary = "已脱敏运行事件",
    outputSummary = null,
    failureCategory = errorCategory.takeUnless { it == "NONE" }?.traceFailureLabel(),
    approval = null,
    approvalReason = null,
    timestampMillis = timestampMillis,
)

private fun String.traceFailureLabel(): String = when (this) {
    "POLICY" -> "策略"
    "APPROVAL" -> "审批"
    "TOOL" -> "工具"
    "PROVIDER" -> "模型服务"
    "CHILD_RUN" -> "子运行"
    "CONTEXT_BUDGET" -> "上下文预算"
    else -> "运行"
}

private fun AgentRunError?.userFacingFailure(): String? = this?.let { error ->
    when (error.category) {
        "lifecycle" -> "运行已停止"
        "preflight" -> "运行准备失败"
        "runtime" -> "运行时发生错误"
        "tool" -> "工具执行失败"
        "context_budget" -> "上下文预算不足"
        else -> "运行发生错误"
    }
}

private fun String?.userFacingApprovalReason(): String? = this?.let {
    "此操作需要你的批准"
}

internal fun durationLabel(start: Long, end: Long): String {
    val millis = (end - start).coerceAtLeast(0)
    return when {
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> "%d.%02ds".format(millis / 1_000, (millis % 1_000) / 10)
        else -> "${millis / 60_000}分${(millis % 60_000) / 1_000}秒"
    }
}

private fun String.statusLabel(): String = when (this) {
    AgentRunStatus.QUEUED.name -> "已排队"
    AgentRunStatus.PREFLIGHT.name -> "准备中"
    AgentRunStatus.RUNNING.name -> "运行中"
    AgentRunStatus.WAITING_APPROVAL.name -> "等待审批"
    AgentRunStatus.SUCCEEDED.name, AgentStepStatus.SUCCEEDED.name, ToolExecutionStatus.SUCCEEDED.name,
    AgentApprovalStatus.APPROVED.name -> "已完成"
    AgentRunStatus.FAILED.name, AgentStepStatus.FAILED.name, ToolExecutionStatus.FAILED.name -> "失败"
    AgentRunStatus.CANCELLED.name, AgentStepStatus.CANCELLED.name, ToolExecutionStatus.CANCELLED.name,
    AgentApprovalStatus.CANCELLED.name -> "已停止"
    AgentRunStatus.INTERRUPTED.name, ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT.name -> "已中断"
    AgentRunStatus.BLOCKED.name -> "已阻止"
    ToolExecutionStatus.PENDING.name -> "等待中"
    ToolExecutionStatus.AUTHORIZED.name -> "已授权"
    ToolExecutionStatus.WAITING_APPROVAL.name, AgentApprovalStatus.PENDING.name -> "等待审批"
    ToolExecutionStatus.DENIED.name, AgentApprovalStatus.DENIED.name -> "已拒绝"
    AgentStepStatus.SKIPPED.name -> "已跳过"
    else -> this.replace('_', ' ')
}

private fun String.statusDescription(): String? = when (this) {
    AgentRunStatus.INTERRUPTED.name -> "运行因应用或进程中断而停止；本批不自动恢复，请在聊天中重新发起。"
    AgentRunStatus.FAILED.name -> "运行未完成；请在聊天中重新发起。"
    AgentRunStatus.CANCELLED.name -> "运行已由用户停止；如仍需要结果，请在聊天中重新发起。"
    AgentRunStatus.BLOCKED.name -> "运行被当前策略阻止；请调整请求或权限后在聊天中重新发起。"
    else -> null
}

private inline fun <reified T> String.decodeOrNull(): T? = runCatching {
    JsonInstant.decodeFromString<T>(this)
}.getOrNull()
