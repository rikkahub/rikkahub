package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed

// 连接式单选按钮组，选中项更宽；选项不多（两到四个）时用来代替下拉框
@Composable
fun <T> ConnectedChoiceButtons(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // 未选中按钮的底色，要和所在容器的底色拉开
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    minHeight: Dp = 48.dp,
    content: @Composable (option: T, selected: Boolean) -> Unit,
) {
    ConnectedButtons(
        options = options,
        isSelected = { it == selected },
        onClick = { option, isSelected -> if (!isSelected) onSelect(option) },
        role = Role.RadioButton,
        growSelected = true,
        modifier = modifier.selectableGroup(),
        containerColor = containerColor,
        minHeight = minHeight,
        content = content,
    )
}

// 连接式多选按钮组，每个按钮各自开关
@Composable
fun <T> ConnectedMultiChoiceButtons(
    options: List<T>,
    selected: Collection<T>,
    onToggle: (option: T, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    minHeight: Dp = 48.dp,
    content: @Composable (option: T, selected: Boolean) -> Unit,
) {
    ConnectedButtons(
        options = options,
        isSelected = { it in selected },
        onClick = { option, isSelected -> onToggle(option, !isSelected) },
        role = Role.Checkbox,
        // 多选时可能同时选中好几个，都变宽没有意义
        growSelected = false,
        modifier = modifier,
        containerColor = containerColor,
        minHeight = minHeight,
        content = content,
    )
}

@Composable
private fun <T> ConnectedButtons(
    options: List<T>,
    isSelected: (T) -> Boolean,
    onClick: (option: T, isSelected: Boolean) -> Unit,
    role: Role,
    growSelected: Boolean,
    modifier: Modifier,
    containerColor: Color,
    minHeight: Dp,
    content: @Composable (option: T, selected: Boolean) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.fastForEachIndexed { index, option ->
            val selected = isSelected(option)
            val weight by animateFloatAsState(
                targetValue = if (selected && growSelected) 1.3f else 1f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            )
            ToggleButton(
                checked = selected,
                onCheckedChange = { onClick(option, selected) },
                modifier = Modifier
                    .weight(weight)
                    .heightIn(min = minHeight)
                    .semantics { this.role = role },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(containerColor = containerColor),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                content(option, selected)
            }
        }
    }
}

// 按钮组里的一行文字，选中时前面带图标
@Composable
fun ConnectedChoiceLabel(
    text: String,
    selected: Boolean,
    icon: ImageVector? = null,
) {
    if (icon != null) {
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            Row {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            }
        }
    }
    Text(
        text = text,
        // 几个按钮并排时较长的译文放不下，自动缩小字号
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
