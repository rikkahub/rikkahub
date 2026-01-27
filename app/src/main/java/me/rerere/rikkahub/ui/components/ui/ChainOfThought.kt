package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles

@Composable
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount

    Card(
        modifier = modifier,
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .animateContentSize(),
        ) {
            val visibleSteps = if (expanded || !canCollapse) {
                steps
            } else {
                steps.takeLast(collapsedVisibleCount)
            }

            // 显示展开/折叠按钮（统一在顶部）
            if (canCollapse) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：图标区域（24.dp，和步骤图标对齐）
                    Box(
                        modifier = Modifier.width(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // 右侧：文字区域（8.dp 间距后开始，和步骤 label 对齐）
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = if (expanded) {
                            "Collapse"
                        } else {
                            "Show ${steps.size - collapsedVisibleCount} more steps"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            visibleSteps.forEachIndexed { index, step ->
                val isFirst = index == 0
                val isLast = index == visibleSteps.lastIndex
                val scope = remember(isFirst, isLast) {
                    ChainOfThoughtScopeImpl(isFirst = isFirst, isLast = isLast)
                }
                scope.content(step)
            }
        }
    }
}

interface ChainOfThoughtScope {
    @Composable
    fun ChainOfThoughtStep(
        icon: ImageVector? = null, // 如果不提供，用点替代
        label: (@Composable () -> Unit),
        status: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null, // 自定义点击行为(如打开bottom sheet)，优先于content的展开行为
        content: (@Composable () -> Unit)? = null, // 如果提供，代表可展开
    )
}

private class ChainOfThoughtScopeImpl(
    private val isFirst: Boolean,
    private val isLast: Boolean
) : ChainOfThoughtScope {
    @Composable
    override fun ChainOfThoughtStep(
        icon: ImageVector?,
        label: @Composable (() -> Unit),
        status: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        content: @Composable (() -> Unit)?
    ) {
        var stepExpanded by remember { mutableStateOf(false) }
        val contentScrollState = rememberScrollState()
        val hasContent = content != null
        val lineColor = MaterialTheme.colorScheme.outlineVariant

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // 左侧：Icon + 连接线（贯穿整个步骤高度）
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        val centerX = size.width / 2
                        val iconSize = 16.dp.toPx()
                        val iconTopPadding = 8.dp.toPx()
                        val gap = 4.dp.toPx()

                        val iconTop = iconTopPadding
                        val iconBottom = iconTopPadding + iconSize

                        // Draw top line
                        if (!isFirst) {
                            drawLine(
                                color = lineColor,
                                start = Offset(centerX, 0f),
                                end = Offset(centerX, iconTop - gap),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Draw bottom line
                        if (!isLast) {
                            drawLine(
                                color = lineColor,
                                start = Offset(centerX, iconBottom + gap),
                                end = Offset(centerX, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                // Icon 容器，带有顶部 padding 对齐 label 行
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // 右侧：内容区域
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                // Label 行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onClick != null) {
                                Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onClick() }
                            } else if (hasContent) {
                                Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { stepExpanded = !stepExpanded }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Label
                    Box(modifier = Modifier.weight(1f)) {
                        label()
                    }

                    // Status
                    if (status != null) {
                        status()
                    }

                    // 指示器：onClick 显示向右箭头，content 显示展开/折叠箭头
                    if (onClick != null) {
                        Icon(
                            imageVector = Lucide.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (hasContent) {
                        Icon(
                            imageVector = if (stepExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 展开内容
                AnimatedVisibility(
                    visible = stepExpanded && hasContent,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp)
                    ) {
                        content?.invoke()
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChainOfThoughtPreview() {
    MaterialTheme {
        Surface {
            ChainOfThought(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                steps = listOf(
                    Triple("Searching", Lucide.Search, "Completed"),
                    Triple("Analyzing results", Lucide.Sparkles, "In progress"),
                    Triple("Step without icon", null, null),
                    Triple("Final step", Lucide.Sparkles, "Done"),
                ),
                collapsedVisibleCount = 2,
            ) { (label, icon, status) ->
                ChainOfThoughtStep(
                    icon = icon,
                    label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    status = status?.let {
                        {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    content = if (label.contains("Search")) {
                        {
                            Text(
                                "This is expandable content for the search step. It can contain more details about the search process.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else null
                )
            }
        }
    }
}
