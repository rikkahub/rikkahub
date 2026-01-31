package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles

private val LocalCardColor = staticCompositionLocalOf { Color.White }

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

    CompositionLocalProvider(
        LocalCardColor provides cardColors.containerColor
    ) {
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
                                stringResource(R.string.chain_of_thought_collapse)
                            } else {
                                stringResource(
                                    R.string.chain_of_thought_show_more_steps,
                                    steps.size - collapsedVisibleCount
                                )
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                val lineColor = MaterialTheme.colorScheme.outlineVariant
                val scope = remember { ChainOfThoughtScopeImpl() }
                Box(
                    modifier = Modifier.drawBehind {
                        val x = 12.dp.toPx()
                        val offsetPx = 18.dp.toPx()
                        drawLine(
                            color = lineColor,
                            start = Offset(x, offsetPx),
                            end = Offset(x, size.height - offsetPx),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                ) {
                    Column {
                        visibleSteps.forEach { step ->
                            scope.content(step)
                        }
                    }
                }
            }
        }
    }
}

interface ChainOfThoughtScope {
    /**
     * 非受控 step：内部管理展开状态
     */
    @Composable
    fun ChainOfThoughtStep(
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        content: (@Composable () -> Unit)? = null,
    )

    /**
     * 受控 step：外部控制展开状态
     */
    @Composable
    fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        contentVisible: Boolean = expanded,
        content: (@Composable () -> Unit)? = null,
    )
}

private class ChainOfThoughtScopeImpl : ChainOfThoughtScope {
    @Composable
    override fun ChainOfThoughtStep(
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        content: @Composable (() -> Unit)?
    ) {
        var expanded by remember { mutableStateOf(false) }
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            contentVisible = expanded,
            content = content,
        )
    }

    @Composable
    override fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            contentVisible = contentVisible,
            content = content,
        )
    }

    @Composable
    private fun ChainOfThoughtStepContent(
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        val hasContent = content != null

        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Label 行：Icon + Label + Extra + 指示器
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
                                .clickable { onExpandedChange(!expanded) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon（不透明背景遮住背后的连线）
                Box(
                    modifier = Modifier.width(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(LocalCardColor.current),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (icon != null) {
                            Box(
                                modifier = Modifier.size(14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                icon()
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                }

                // Label
                Box(modifier = Modifier.weight(1f)) {
                    label()
                }

                // Extra
                if (extra != null) {
                    extra()
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
                        imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 展开内容（缩进对齐 label）
            if (contentVisible && hasContent) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    content?.invoke()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChainOfThoughtPreview() {
    // 定义步骤数据类
    data class StepData(
        val label: String,
        val icon: ImageVector?,
        val status: String?,
        val hasContent: Boolean = false,
        val hasOnClick: Boolean = false,
        val controlled: Boolean = false,
    )

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Chain of thought")
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding),
            ) {
                // 受控状态示例
                var controlledExpanded by remember { mutableStateOf(false) }

                ChainOfThought(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    steps = listOf(
                        StepData("Searching the web", Lucide.Search, "3 results", hasContent = true),
                        StepData("Reading documents", Lucide.Sparkles, "Completed", hasOnClick = true),
                        StepData("Analyzing results (controlled)", Lucide.Sparkles, "In progress", hasContent = true, controlled = true),
                        StepData("Step without icon", null, null),
                        StepData("Final step", Lucide.Sparkles, "Done"),
                    ),
                    collapsedVisibleCount = 2,
                ) { step ->
                    val iconComposable: (@Composable () -> Unit)? = step.icon?.let {
                        {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val labelComposable: @Composable () -> Unit = {
                        Text(step.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    val extraComposable: (@Composable () -> Unit)? = step.status?.let {
                        {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val onClickHandler: (() -> Unit)? = if (step.hasOnClick) {
                        { /* Open bottom sheet */ }
                    } else null
                    val contentComposable: (@Composable () -> Unit)? = if (step.hasContent) {
                        {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (step.label.contains("Search")) {
                                    listOf(
                                        "example.com - Example Domain",
                                        "docs.example.com - Documentation",
                                        "blog.example.com - Blog Post"
                                    ).forEach { result ->
                                        Text(
                                            text = "• $result",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "This is expandable content showing detailed analysis. " +
                                            "It can contain multiple lines of text, code snippets, " +
                                            "or any other composable content.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    } else null

                    if (step.controlled) {
                        // 受控版本
                        ControlledChainOfThoughtStep(
                            expanded = controlledExpanded,
                            onExpandedChange = { controlledExpanded = it },
                            icon = iconComposable,
                            label = labelComposable,
                            extra = extraComposable,
                            onClick = onClickHandler,
                            content = contentComposable,
                        )
                    } else {
                        // 非受控版本
                        ChainOfThoughtStep(
                            icon = iconComposable,
                            label = labelComposable,
                            extra = extraComposable,
                            onClick = onClickHandler,
                            content = contentComposable,
                        )
                    }
                }
            }
        }
    }
}
