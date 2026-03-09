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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R

private val LocalCardColor = staticCompositionLocalOf { Color.White }

/**
 * 以时间线/步骤卡片的形式展示一组思考过程。
 *
 * 适用于承载推理步骤、工具调用步骤，或两者混合的链式内容。组件支持：
 * - 在步骤较多时自动折叠，仅展示最后若干步
 * - 点击顶部控制条展开/收起全部步骤
 * - 通过 [collapsedAdaptiveWidth] 控制折叠态是否保持自适应宽度
 *
 * @param modifier 外层卡片的修饰符
 * @param cardColors 卡片配色
 * @param steps 需要渲染的步骤数据列表
 * @param collapsedVisibleCount 折叠时保留可见的尾部步骤数
 * @param collapsedAdaptiveWidth 是否在折叠态下使用内容自适应宽度
 * @param content 每个步骤的具体 UI，由 [ChainOfThoughtScope] 提供步骤构建能力
 */
@Composable
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    collapsedAdaptiveWidth: Boolean = false,
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth

    CompositionLocalProvider(
        LocalCardColor provides cardColors.containerColor
    ) {
        Card(
            modifier = modifier,
            colors = cardColors,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateContentSize(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
                    ),
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
                            .then(
                                if (shouldFillCollapseControlWidth) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier
                                }
                            )
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
                                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
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
                        visibleSteps.fastForEach { step ->
                            scope.content(step)
                        }
                    }
                }
            }
        }
    }
}

/**
 * [ChainOfThought] 内部使用的步骤渲染作用域。
 *
 * 通过该作用域可以声明单个步骤的图标、标题、附加信息以及可展开内容，
 * 并复用统一的时间线布局与交互行为。
 */
interface ChainOfThoughtScope {
    /**
     * 声明一个非受控步骤，由组件内部管理展开/折叠状态。
     *
     * @param icon 步骤图标
     * @param label 步骤标题区域
     * @param extra 标题右侧的附加信息
     * @param onClick 自定义点击行为；设置后优先于展开/折叠逻辑
     * @param collapsedAdaptiveWidth 是否在折叠且内容隐藏时使用自适应宽度
     * @param content 步骤展开后显示的内容；为 `null` 时步骤不可展开
     */
    @Composable
    fun ChainOfThoughtStep(
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        collapsedAdaptiveWidth: Boolean = false,
        content: (@Composable () -> Unit)? = null,
    )

    /**
     * 声明一个受控步骤，由外部传入展开状态。
     *
     * 适合需要与外部状态联动的场景，例如“推理中预览 / 完成后收起”。
     *
     * @param expanded 当前是否处于展开状态
     * @param onExpandedChange 展开状态变化回调
     * @param icon 步骤图标
     * @param label 步骤标题区域
     * @param extra 标题右侧的附加信息
     * @param onClick 自定义点击行为；设置后优先于展开/折叠逻辑
     * @param collapsedAdaptiveWidth 是否在折叠且内容隐藏时使用自适应宽度
     * @param contentVisible 是否展示内容区域，可与 [expanded] 解耦
     * @param content 步骤内容；为 `null` 时步骤不可展开
     */
    @Composable
    fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        collapsedAdaptiveWidth: Boolean = false,
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
        collapsedAdaptiveWidth: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        var expanded by remember { mutableStateOf(false) }
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            collapsedAdaptiveWidth = collapsedAdaptiveWidth,
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
        collapsedAdaptiveWidth: Boolean,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            collapsedAdaptiveWidth = collapsedAdaptiveWidth,
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
        collapsedAdaptiveWidth: Boolean,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        val hasContent = content != null
        val shouldFillMaxWidth = !collapsedAdaptiveWidth || contentVisible

        Column(
            modifier = Modifier.then(
                if (shouldFillMaxWidth) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                }
            ),
        ) {
            // Label 行：Icon + Label + Extra + 指示器
            Row(
                modifier = Modifier
                    .then(
                        if (shouldFillMaxWidth) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier
                        }
                    )
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
                Box(
                    modifier = Modifier.then(
                        if (shouldFillMaxWidth) {
                            Modifier.weight(1f)
                        } else {
                            Modifier
                        }
                    )
                ) {
                    label()
                }

                // Extra
                if (extra != null) {
                    extra()
                }

                // 指示器：onClick 显示向右箭头，content 显示展开/折叠箭头
                if (onClick != null) {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (hasContent) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
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
                        .then(
                            if (shouldFillMaxWidth) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier
                            }
                        )
                        .padding(start = 32.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    content()
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
                        StepData("Searching the web", HugeIcons.Search01, "3 results", hasContent = true),
                        StepData("Reading documents", HugeIcons.Sparkles, "Completed", hasOnClick = true),
                        StepData(
                            "Analyzing results (controlled)",
                            HugeIcons.Sparkles,
                            "In progress",
                            hasContent = true,
                            controlled = true
                        ),
                        StepData("Step without icon", null, null),
                        StepData("Final step", HugeIcons.Sparkles, "Done"),
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
