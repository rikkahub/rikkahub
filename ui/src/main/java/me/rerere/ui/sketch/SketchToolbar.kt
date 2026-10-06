package me.rerere.ui.sketch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CursorRectangleSelection01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.Type
import me.rerere.ui.R

/**
 * 画纸下方的工具栏：粗细、画笔、文字、选择、橡皮和颜色。
 */
@Composable
internal fun SketchToolbar(state: SketchState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SketchDefaults.BrushWidths.forEach { width ->
                    WidthOption(
                        width = width,
                        selected = state.width == width,
                        onClick = { state.width = width },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            SketchBrushMenu(state = state)
            FilledTonalIconToggleButton(
                checked = state.tool == SketchTool.Text,
                onCheckedChange = state::useText,
            ) {
                Icon(HugeIcons.Type, contentDescription = stringResource(R.string.sketch_dialog_text))
            }
            FilledTonalIconToggleButton(
                checked = state.tool == SketchTool.Select,
                onCheckedChange = state::useSelect,
            ) {
                Icon(
                    HugeIcons.CursorRectangleSelection01,
                    contentDescription = stringResource(R.string.sketch_dialog_select),
                )
            }
            FilledTonalIconToggleButton(
                checked = state.erasing,
                onCheckedChange = state::useEraser,
            ) {
                Icon(HugeIcons.Eraser, contentDescription = stringResource(R.string.sketch_dialog_eraser))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SketchDefaults.BrushColors.forEach { color ->
                ColorOption(
                    color = color,
                    selected = !state.erasing && !state.usingCustomColor && state.color == color,
                    onClick = { state.usePreset(color) },
                )
            }
            val customSelected = !state.erasing && state.usingCustomColor
            ColorOption(
                color = state.customColor,
                selected = customSelected,
                custom = true,
                onClick = {
                    val custom = state.customColor
                    when {
                        // 第一次点：给一个起始色，直接开始调
                        custom == null -> {
                            state.useCustom(SketchDefaults.CustomColor)
                            state.pickingColor = true
                        }
                        // 调过之后，点一下是选中它，选中时再点才是打开或收起调色面板
                        !customSelected -> state.useCustom(custom)
                        else -> state.pickingColor = !state.pickingColor
                    }
                },
            )
        }
    }
}

// 中间的圆点就是这一档的粗细。荧光笔画出来比它宽，文字的字号也跟着这一档走
@Composable
private fun WidthOption(width: Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width)
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = CircleShape,
                ),
        )
    }
}

// [custom] 是自定义颜色的那一格：外面一圈彩虹，调过颜色之后中间显示调出来的颜色
@Composable
private fun ColorOption(
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    custom: Boolean = false,
) {
    val customLabel = stringResource(R.string.sketch_dialog_custom_color)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .then(if (custom) Modifier.semantics { contentDescription = customLabel } else Modifier)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(6.dp)
            .then(
                if (custom) {
                    Modifier
                        .background(Brush.sweepGradient(SketchDefaults.HueColors), CircleShape)
                        .padding(4.dp)
                } else {
                    Modifier
                }
            )
            .then(if (color != null) Modifier.background(color, CircleShape) else Modifier)
            // 深色主题下黑色的色块要靠描边才看得出来
            .then(
                if (custom) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                }
            ),
    )
}
