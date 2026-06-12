package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Coins01
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * The presentation-facing status of one task run, derived from the lifecycle tags the spawn
 * ("task") tool mirrors into its `UIMessagePart.Tool` output. This is a UI-only collapse of the
 * pure `me.rerere.ai.runtime.task.TaskState` machine (whose neutral domain this `:app` renderer
 * must not import a Compose dependency into): the in-flight states (Created/Queued/Starting/
 * Running/Resuming) all read as "running" to the user, and the terminals keep their identity.
 */
enum class TaskRunDisplayStatus {
    Running,
    WaitingApproval,
    Succeeded,
    Failed,
    Cancelled,
    BudgetExhausted,
    Interrupted,
}

/**
 * The flattened, render-ready view of a task run. Produced purely from the tool output JSON by
 * [TaskToolUI.taskState] (the single, JVM-testable parse), so the Compose layer below only ever
 * reads fields — never the raw JSON. Keeping this a plain data class is what lets the parse be
 * unit-tested without a Compose runtime (SPEC.md M5 testing seam).
 *
 * @param resumable true only for [TaskRunDisplayStatus.Interrupted]: the resume affordance is the
 *   ONLY edge out of an interrupted run (maintainer decisions #1/#3); v1 renders a placeholder.
 * @param pendingChildApproval true while an allowlisted child tool waits on the parent's approval
 *   surface — surfaced parent-visible, never executed hidden (maintainer decision #2).
 */
data class TaskToolViewState(
    val status: TaskRunDisplayStatus,
    val summary: String?,
    val steps: Int?,
    val maxSteps: Int?,
    val tokens: Long?,
    val pendingChildApproval: Boolean,
    val pendingApprovalToolName: String?,
    val resumable: Boolean,
)

/**
 * Live renderer for the spawn ("task") tool (SPEC.md M5). Registered in [ToolUIRegistry] so a
 * `Tool(name="task")` step renders its lifecycle — status, budget counters, progress summary,
 * a pending child-approval banner, and a resume affordance placeholder — instead of the generic
 * JSON dump.
 *
 * It reads the live state out of the EXISTING `UIMessagePart.Tool` output: no new `UIMessagePart`
 * subtype, no new `@SerialName` (v1 prohibition). The output is the structured `{task:{...}}`
 * envelope the coordinator mirrors; whenever that envelope is absent or malformed — every pre-M5
 * transcript carries a bare final-text string — [taskState] degrades to a completed task whose
 * summary IS that text, so old transcripts keep rendering and never crash the chat UI.
 */
object TaskToolUI : ToolUIRenderer {
    override val toolName: String = "task"

    override fun icon(context: ToolUIContext): ImageVector =
        statusIcon(taskState(context).status)

    @Composable
    override fun title(context: ToolUIContext): String {
        // The subagent the parent delegated to (the spawn tool's `subagent` arg) is the most
        // useful label; fall back to a generic title when it is absent.
        val sub = context.arguments.getStringContent("subagent")
        return if (sub != null) "Task: $sub" else "Task"
    }

    override fun hasSummary(context: ToolUIContext): Boolean {
        val state = taskState(context)
        return state.summary != null || state.pendingChildApproval || state.steps != null
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val state = taskState(context)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusRow(state = state, loading = context.loading)
            if (state.steps != null) {
                BudgetRow(state = state)
            }
            state.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.shimmer(isLoading = context.loading),
                )
            }
            if (state.pendingChildApproval) {
                PendingApprovalBanner(toolName = state.pendingApprovalToolName)
            }
            if (state.resumable) {
                ResumePlaceholder()
            }
        }
    }

    @Composable
    private fun StatusRow(state: TaskToolViewState, loading: Boolean) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = statusIcon(state.status),
                contentDescription = null,
                modifier = Modifier.size(16.dp).shimmer(isLoading = loading),
            )
            Text(
                text = statusLabel(state.status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    @Composable
    private fun BudgetRow(state: TaskToolViewState) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CounterChip(
                icon = HugeIcons.Layers01,
                text = if (state.maxSteps != null) "${state.steps}/${state.maxSteps}" else "${state.steps}",
            )
            state.tokens?.takeIf { it > 0 }?.let { tokens ->
                CounterChip(icon = HugeIcons.Coins01, text = tokens.toString())
            }
        }
    }

    @Composable
    private fun CounterChip(icon: ImageVector, text: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }

    @Composable
    private fun PendingApprovalBanner(toolName: String?) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Alert01,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = if (toolName != null) {
                        "Subagent is waiting for your approval: $toolName"
                    } else {
                        "Subagent is waiting for your approval"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }

    @Composable
    private fun ResumePlaceholder() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.ArrowTurnBackward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Text(
                text = "Interrupted — resume available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }

    private fun statusIcon(status: TaskRunDisplayStatus): ImageVector = when (status) {
        TaskRunDisplayStatus.Running -> HugeIcons.AiBrain01
        TaskRunDisplayStatus.WaitingApproval -> HugeIcons.Alert01
        TaskRunDisplayStatus.Succeeded -> HugeIcons.CheckmarkCircle01
        TaskRunDisplayStatus.Failed -> HugeIcons.Cancel01
        TaskRunDisplayStatus.Cancelled -> HugeIcons.Cancel01
        TaskRunDisplayStatus.BudgetExhausted -> HugeIcons.Clock02
        TaskRunDisplayStatus.Interrupted -> HugeIcons.PauseCircle
    }

    private fun statusLabel(status: TaskRunDisplayStatus): String = when (status) {
        TaskRunDisplayStatus.Running -> "Running"
        TaskRunDisplayStatus.WaitingApproval -> "Waiting for approval"
        TaskRunDisplayStatus.Succeeded -> "Done"
        TaskRunDisplayStatus.Failed -> "Failed"
        TaskRunDisplayStatus.Cancelled -> "Cancelled"
        TaskRunDisplayStatus.BudgetExhausted -> "Budget exhausted"
        TaskRunDisplayStatus.Interrupted -> "Interrupted"
    }

    /**
     * Parse the live task state out of the tool output JSON. THE pure, JVM-tested seam: the
     * Compose layer above is a thin projection of this result.
     *
     * Resolution order, deliberately fail-soft so a transcript never crashes the chat UI:
     *  1. No output yet (tool still generating) -> [TaskRunDisplayStatus.Running], no summary.
     *  2. A structured `{task:{status, summary, budget:{steps,maxSteps,tokens}, pendingApproval}}`
     *     envelope -> read its fields; an unknown status string degrades to `running` rather than
     *     throwing.
     *  3. Anything else — the bare final-text output every pre-M5 transcript carries — is treated
     *     as a completed task whose summary IS that text (the legacy spawn-tool contract).
     */
    fun taskState(context: ToolUIContext): TaskToolViewState {
        val executed = context.tool.output.isNotEmpty()
        if (!executed) {
            return TaskToolViewState(
                status = TaskRunDisplayStatus.Running,
                summary = null,
                steps = null,
                maxSteps = null,
                tokens = null,
                pendingChildApproval = false,
                pendingApprovalToolName = null,
                resumable = false,
            )
        }

        val task = (context.content as? JsonObject)?.get("task")?.jsonObjectOrNull
        if (task == null) {
            // Legacy / fallback: bare final text. The whole output text is the summary.
            val text = context.tool.output
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { null }
            return TaskToolViewState(
                status = TaskRunDisplayStatus.Succeeded,
                summary = text,
                steps = null,
                maxSteps = null,
                tokens = null,
                pendingChildApproval = false,
                pendingApprovalToolName = null,
                resumable = false,
            )
        }

        val status = parseStatus(task.getStringContent("status"))
        val budget = task["budget"]?.jsonObjectOrNull
        val approval = task["pendingApproval"]?.jsonObjectOrNull
        return TaskToolViewState(
            status = status,
            summary = task.getStringContent("summary"),
            steps = budget?.get("steps")?.jsonPrimitiveOrNull?.intOrNull,
            maxSteps = budget?.get("maxSteps")?.jsonPrimitiveOrNull?.intOrNull,
            tokens = budget?.get("tokens")?.jsonPrimitiveOrNull?.longOrNull,
            pendingChildApproval = approval != null || status == TaskRunDisplayStatus.WaitingApproval,
            pendingApprovalToolName = approval.getStringContent("toolName"),
            resumable = status == TaskRunDisplayStatus.Interrupted,
        )
    }

    /** Wire status names, stable lower_snake. Unknown -> Running (never crash on drift). */
    private fun parseStatus(wire: String?): TaskRunDisplayStatus = when (wire) {
        "running", "created", "queued", "starting", "resuming" -> TaskRunDisplayStatus.Running
        "waiting_approval" -> TaskRunDisplayStatus.WaitingApproval
        "succeeded" -> TaskRunDisplayStatus.Succeeded
        "failed" -> TaskRunDisplayStatus.Failed
        "cancelled" -> TaskRunDisplayStatus.Cancelled
        "budget_exhausted" -> TaskRunDisplayStatus.BudgetExhausted
        "interrupted" -> TaskRunDisplayStatus.Interrupted
        else -> TaskRunDisplayStatus.Running
    }
}
