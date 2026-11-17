package me.rerere.rikkahub.ui.pages.quickstart

import me.rerere.rikkahub.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.ui.components.ui.CardGroupScope

internal enum class StepState { DONE, TODO, OPTIONAL }

// 页面顶部的说明卡片：异形图标 + 标题说明，下面可以接进度或按钮
@Composable
internal fun QuickStartHeroCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            content()
        }
    }
}

// 步骤序号：完成后变成带勾的饼干形，未完成是圆形数字
@Composable
private fun StepBadge(index: Int, state: StepState) {
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            StepState.DONE -> MaterialTheme.colorScheme.primary
            StepState.TODO -> MaterialTheme.colorScheme.tertiaryContainer
            StepState.OPTIONAL -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val contentColor = when (state) {
        StepState.DONE -> MaterialTheme.colorScheme.onPrimary
        StepState.TODO -> MaterialTheme.colorScheme.onTertiaryContainer
        StepState.OPTIONAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AnimatedContent(
        targetState = state == StepState.DONE,
        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
        label = "StepBadge",
    ) { done ->
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(containerColor, if (done) MaterialShapes.Cookie6Sided.toShape() else CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = HugeIcons.Tick02,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor,
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun StepStatusLabel(state: StepState) {
    val (text, container, content) = when (state) {
        StepState.DONE -> Triple(
            stringResource(R.string.quick_start_page_step_done),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        StepState.TODO -> Triple(
            stringResource(R.string.quick_start_page_step_todo),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        StepState.OPTIONAL -> Triple(
            stringResource(R.string.quick_start_page_step_optional),
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    StatusPill(text = text, containerColor = container, contentColor = content)
}

@Composable
internal fun StatusPill(text: String, containerColor: Color, contentColor: Color) {
    Surface(shape = CircleShape, color = containerColor, contentColor = contentColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

// 一个配置步骤：序号、标题、说明和状态，下面接操作（按钮、模型选择等）
// CardGroup 的 DSL 不是 Composable 作用域，文案传资源 id，在各自的槽位里再解析
internal fun CardGroupScope.stepItem(
    index: Int,
    @StringRes title: Int,
    @StringRes description: Int,
    state: StepState,
    descriptionArgs: List<Any> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) = formItem(
    headlineContent = {
        Text(text = stringResource(title), style = MaterialTheme.typography.titleMediumEmphasized)
    },
    supportingContent = { Text(stringResource(description, *descriptionArgs.toTypedArray())) },
    leadingContent = { StepBadge(index = index, state = state) },
    trailingContent = { StepStatusLabel(state) },
    content = content,
)
