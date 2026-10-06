package me.rerere.ui.sketch

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02

/**
 * 画笔按钮：显示当前的画笔，点开可以换一种。正在用别的工具时点它是切回画笔。
 */
@Composable
internal fun SketchBrushMenu(state: SketchState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconToggleButton(
            checked = state.tool == SketchTool.Brush,
            onCheckedChange = {
                if (state.tool == SketchTool.Brush) expanded = true else state.useBrush(state.brush)
            },
        ) {
            Icon(state.brush.icon, contentDescription = stringResource(state.brush.label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SketchBrush.entries.forEach { brush ->
                DropdownMenuItem(
                    text = { Text(stringResource(brush.label)) },
                    leadingIcon = { Icon(brush.icon, contentDescription = null) },
                    trailingIcon = if (state.brush == brush) {
                        { Icon(HugeIcons.Tick02, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        state.useBrush(brush)
                    },
                )
            }
        }
    }
}
