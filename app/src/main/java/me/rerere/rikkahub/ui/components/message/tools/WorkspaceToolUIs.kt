package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import me.rerere.rikkahub.utils.generateUnifiedDiff

/**
 * 工作空间编辑文件: 摘要显示增删统计与精简 diff, 详情为完整 diff view
 */
object EditFileToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 10

    override val toolName: String = "workspace_edit_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) "Edit: $path" else "Edit file"
    }

    /**
     * 执行后读取输出部件 metadata 中的全文件 diff;
     * 未执行 (如等待审批) 时基于入参的 old_text/new_text 片段生成预览 diff
     */
    private fun diffOf(context: ToolUIContext): String? {
        if (context.tool.isExecuted) {
            return context.tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff
        }
        val path = context.arguments.getStringContent("path") ?: return null
        val oldText = context.arguments.getStringContent("old_text") ?: return null
        val newText = context.arguments.getStringContent("new_text") ?: return null
        return generateUnifiedDiff(oldText, newText, path)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = diffOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = remember(context) { diffOf(context) } ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "+${stats.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffAddedColor,
            )
            Text(
                text = "-${stats.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffRemovedColor,
            )
        }
        DiffView(
            diff = diff,
            modifier = Modifier.fillMaxWidth(),
            maxLines = SUMMARY_MAX_LINES,
            showFileHeader = false,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { diffOf(context) }
        if (diff == null) {
            DefaultToolPreview(context = context)
            return
        }
        val stats = remember(diff) { parseDiffStats(diff) }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.arguments.getStringContent("path") ?: toolName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemovedColor,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
