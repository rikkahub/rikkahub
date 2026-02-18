package me.rerere.rikkahub.ui.components.container

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import me.rerere.rikkahub.data.container.ContainerStateEnum
import me.rerere.rikkahub.ui.components.workflow.WorkflowMenuCard

/**
 * Workflow 菜单中的容器运行时入口卡片
 */
@Composable
fun ContainerRuntimeCard(
    state: ContainerStateEnum,
    onClick: () -> Unit
) {
    val (statusText, statusColor) = when (state) {
        is ContainerStateEnum.NotInitialized ->
            Pair("未初始化", MaterialTheme.colorScheme.onSurfaceVariant)
        is ContainerStateEnum.Initializing ->
            Pair("准备中", MaterialTheme.colorScheme.tertiary)
        is ContainerStateEnum.Running ->
            Pair("运行中", MaterialTheme.colorScheme.primary)
        is ContainerStateEnum.Stopped ->
            Pair("已停止", MaterialTheme.colorScheme.onSurfaceVariant)
        is ContainerStateEnum.Error ->
            Pair("错误", MaterialTheme.colorScheme.error)
    }

    WorkflowMenuCard(
        icon = Lucide.Terminal,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "容器运行时",
        subtitle = statusText,
        subtitleColor = statusColor,
        onClick = onClick
    )
}
