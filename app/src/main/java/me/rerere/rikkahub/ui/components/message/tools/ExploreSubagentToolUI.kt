package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * Explore 子运行仅展示持久化标识和结构化摘要，不显示工具输入、输出或轨迹。
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
        val data = ExploreReportData.from(context) ?: return
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(success = true, error = null)
            }
            if (data.findings.isNotBlank()) {
                Text(
                    text = data.findings.take(180),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            data.childRunId?.let { childRunId ->
                Text(
                    text = "Run ${childRunId.takeLast(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val data = remember(context) { ExploreReportData.from(context) }
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
                StatusChip(success = true, error = null)
            }

            // Task
            SectionCard(title = stringResource(R.string.chat_message_tool_explore_subagent_task_label)) {
                Text(
                    text = data.task.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard(title = stringResource(R.string.chat_message_tool_explore_subagent_report)) {
                if (data.findings.isNotBlank()) {
                    MarkdownBlock(
                        content = data.findings,
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

private data class ExploreReportData(
    val task: String,
    val childRunId: String?,
    val findings: String,
) {
    companion object {
        fun from(context: ToolUIContext): ExploreReportData? {
            val content = context.content as? JsonObject ?: return null
            val task = context.arguments.getStringContent("task").orEmpty()
            val childRunId = content["child_run_id"]?.jsonPrimitive?.contentOrNull
            val findings = content["findings"]?.jsonArray?.joinToString("\n") {
                it.jsonPrimitive.contentOrNull.orEmpty()
            }.orEmpty()
            return ExploreReportData(
                task = task,
                childRunId = childRunId,
                findings = findings,
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
