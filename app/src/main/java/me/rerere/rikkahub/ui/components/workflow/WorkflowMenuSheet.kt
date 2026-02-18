package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import me.rerere.rikkahub.data.container.ContainerStateEnum
import me.rerere.rikkahub.data.model.WorkflowPhase
import me.rerere.rikkahub.ui.components.container.ContainerRuntimeCard
import androidx.compose.ui.graphics.Color

/**
 * Workflow 菜单展开界面
 *
 * 圆角卡片式布局，包含：
 * 1. 模式选择（PLAN/EXECUTE/REVIEW）- 卡片式设计
 * 2. 容器运行时入口 - 点击展开管理弹窗
 * 3. 沙箱文件管理入口 - 独立卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowMenuSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    currentPhase: WorkflowPhase,
    onPhaseChange: (WorkflowPhase) -> Unit,
    onOpenSandbox: () -> Unit,
    // 容器运行时参数（简化）
    containerState: ContainerStateEnum,
    onOpenContainerManager: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "工作流",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 模式选择卡片列表
                WorkflowPhase.entries.forEach { phase ->
                    val isSelected = currentPhase == phase
                    val phaseColor = getPhaseColor(phase)

                    PhaseCard(
                        phase = phase,
                        description = getPhaseDescription(phase),
                        isSelected = isSelected,
                        phaseColor = phaseColor,
                        onClick = { onPhaseChange(phase) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 容器运行时入口卡片（简化，点击展开管理弹窗）
                ContainerRuntimeCard(
                    state = containerState,
                    onClick = onOpenContainerManager
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 沙箱文件管理卡片
                SandboxCard(
                    onClick = onOpenSandbox
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 底部提示文字
                Text(
                    text = "点击卡片管理对应功能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PhaseCard(
    phase: WorkflowPhase,
    description: String,
    isSelected: Boolean,
    phaseColor: Color,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        phaseColor.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val borderColor = if (isSelected) {
        phaseColor
    } else {
        Color.Transparent
    }

    val textColor = if (isSelected) {
        phaseColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 阶段名称
            Text(
                text = phase.name,
                style = MaterialTheme.typography.titleSmall,
                color = textColor
            )

            // 描述文字
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    textColor.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        }
    }
}

@Composable
private fun SandboxCard(
    onClick: () -> Unit
) {
    WorkflowMenuCard(
        icon = Lucide.FolderOpen,
        iconTint = MaterialTheme.colorScheme.secondary,
        title = "沙箱文件管理",
        subtitle = "管理对话文件和目录",
        onClick = onClick
    )
}

@Composable
private fun getPhaseColor(phase: WorkflowPhase): Color {
    return when (phase) {
        WorkflowPhase.PLAN -> Color(0xFF4A90D9) // 蓝色
        WorkflowPhase.EXECUTE -> Color(0xFF7B7B7B) // 灰色
        WorkflowPhase.REVIEW -> Color(0xFF7B7B7B) // 灰色
    }
}

private fun getPhaseDescription(phase: WorkflowPhase): String {
    return when (phase) {
        WorkflowPhase.PLAN -> "该模式用于分析需求并制定执行计划"
        WorkflowPhase.EXECUTE -> "该模式用于执行代码与自动化任务"
        WorkflowPhase.REVIEW -> "该模式用于审查代码质量与安全问题"
    }
}
