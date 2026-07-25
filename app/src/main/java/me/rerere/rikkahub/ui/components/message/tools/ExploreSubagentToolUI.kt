package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * Explore Subagent 轨迹观测面板。
 * 折叠摘要 + 点击展开：任务 / 状态 / 工具时间线 / 报告。
 */
object ExploreSubagentToolUI : ToolUIRenderer {
    override val toolName: String = "explore_subagent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String {
        val task = context.arguments.getStringContent("task")?.take(48).orEmpty()
        return if (task.isBlank()) {
            stringResource(R.string.chat_message_tool_explore_subagent)
        } else {
            stringResource(R.string.chat_message_tool_explore_subagent_task, task)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content is JsonObject

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (context.loading) {
            Text(
                text = stringResource(R.string.chat_message_tool_explore_subagent_running),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = true),
            )
            return
        }
        val data = ExploreTraceData.from(context) ?: return
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(success = data.success, error = data.error)
                Text(
                    text = stringResource(
                        R.string.chat_message_tool_explore_subagent_meta,
                        data.stepsUsed,
                        data.toolsUsed.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            if (data.summary.isNotBlank()) {
                Text(
                    text = data.summary.take(180),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (data.toolsUsed.isNotEmpty()) {
                Text(
                    text = data.toolsUsed.joinToString(" → "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val data = remember(context) { ExploreTraceData.from(context) }
        if (data == null) {
            DefaultToolPreview(context = context)
            return
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.GlobalSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_explore_subagent_panel_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(success = data.success, error = data.error)
            }

            // Task
            SectionCard(title = stringResource(R.string.chat_message_tool_explore_subagent_task_label)) {
                Text(
                    text = data.task.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Meta chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                R.string.chat_message_tool_explore_subagent_steps,
                                data.stepsUsed,
                            )
                        )
                    },
                )
                data.toolsUsed.forEach { name ->
                    AssistChip(
                        onClick = {},
                        label = { Text(name) },
                    )
                }
            }

            if (!data.error.isNullOrBlank()) {
                Text(
                    text = data.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Trace timeline
            if (data.trace.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.chat_message_tool_explore_subagent_trace)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        data.trace.forEach { step ->
                            TraceStepRow(step = step)
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.chat_message_tool_explore_subagent_trace_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // Report
            SectionCard(title = stringResource(R.string.chat_message_tool_explore_subagent_report)) {
                if (data.summary.isNotBlank()) {
                    MarkdownBlock(
                        content = data.summary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class ExploreTraceStepUi(
    val index: Int,
    val toolName: String,
    val input: String,
    val output: String,
    val isError: Boolean,
)

private data class ExploreTraceData(
    val task: String,
    val success: Boolean,
    val summary: String,
    val stepsUsed: Int,
    val toolsUsed: List<String>,
    val trace: List<ExploreTraceStepUi>,
    val error: String?,
) {
    companion object {
        fun from(context: ToolUIContext): ExploreTraceData? {
            val content = context.content as? JsonObject ?: return null
            val task = context.arguments.getStringContent("task").orEmpty()
            val success = content["success"]?.jsonPrimitive?.booleanOrNull ?: true
            val summary = content["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val stepsUsed = content["steps_used"]?.jsonPrimitive?.intOrNull ?: 0
            val toolsUsed = content["tools_used_list"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: content["tools_used"]?.jsonPrimitive?.contentOrNull
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val trace = (content["trace"] as? JsonArray)?.mapIndexedNotNull { i, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                ExploreTraceStepUi(
                    index = obj["index"]?.jsonPrimitive?.intOrNull ?: i,
                    toolName = obj["tool"]?.jsonPrimitive?.contentOrNull ?: "tool",
                    input = obj["input"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    output = obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    isError = obj["error"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            } ?: emptyList()
            val error = content["error"]?.jsonPrimitive?.contentOrNull
            return ExploreTraceData(
                task = task,
                success = success,
                summary = summary,
                stepsUsed = stepsUsed,
                toolsUsed = toolsUsed,
                trace = trace,
                error = error,
            )
        }
    }
}

@Composable
private fun StatusChip(success: Boolean, error: String?) {
    val ok = success && error.isNullOrBlank()
    val bg = if (ok) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val fg = if (ok) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (ok) {
                stringResource(R.string.chat_message_tool_explore_subagent_status_ok)
            } else {
                stringResource(R.string.chat_message_tool_explore_subagent_status_fail)
            },
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

@Composable
private fun TraceStepRow(step: ExploreTraceStepUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (step.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    ),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${step.index + 1}. ${step.toolName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (step.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (step.input.isNotBlank()) {
                TraceMonoBlock(
                    label = stringResource(R.string.chat_message_tool_explore_subagent_input),
                    text = step.input,
                )
            }
            if (step.output.isNotBlank()) {
                TraceMonoBlock(
                    label = stringResource(R.string.chat_message_tool_explore_subagent_output),
                    text = step.output,
                    isError = step.isError,
                )
            }
        }
    }
}

@Composable
private fun TraceMonoBlock(
    label: String,
    text: String,
    isError: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
