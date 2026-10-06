package me.rerere.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

private val ColorSliderThumbRadius = 14.dp

/**
 * 用色相、饱和度、明度三条滑杆调自定义颜色，拖动时画笔颜色跟着变。
 */
@Composable
internal fun SketchColorPicker(state: SketchState, modifier: Modifier = Modifier) {
    // 三个分量各自记着：颜色调成黑白灰之后，从颜色本身已经反推不出色相了
    val hsv = remember {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV((state.customColor ?: SketchDefaults.CustomColor).toArgb(), it)
        }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color.hsv(hue, saturation, value)

    fun update(newHue: Float, newSaturation: Float, newValue: Float) {
        hue = newHue
        saturation = newSaturation
        value = newValue
        state.useCustom(Color.hsv(newHue, newSaturation, newValue))
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ColorSlider(
                value = hue,
                valueRange = 0f..360f,
                trackColors = SketchDefaults.HueColors,
                thumbColor = Color.hsv(hue, 1f, 1f),
                onValueChange = { update(it, saturation, value) },
            )
            ColorSlider(
                value = saturation,
                valueRange = 0f..1f,
                trackColors = listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value)),
                thumbColor = color,
                onValueChange = { update(hue, it, value) },
            )
            ColorSlider(
                value = value,
                valueRange = 0f..1f,
                trackColors = listOf(Color.Black, Color.hsv(hue, saturation, 1f)),
                thumbColor = color,
                onValueChange = { update(hue, saturation, it) },
            )
        }
    }
}

// 轨道画成渐变：拖到哪里就是哪里的颜色
@Composable
private fun ColorSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    trackColors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val span = valueRange.endInclusive - valueRange.start
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress {
                    currentOnValueChange(it.coerceIn(valueRange))
                    true
                }
            }
            .pointerInput(valueRange) {
                // 两端各留出滑块的半径，滑块不会滑到轨道外面
                val inset = ColorSliderThumbRadius.toPx()
                fun update(x: Float) {
                    val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                    currentOnValueChange(valueRange.start + fraction * span)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    update(down.position.x)
                    drag(down.id) {
                        it.consume()
                        update(it.position.x)
                    }
                }
            },
    ) {
        val inset = ColorSliderThumbRadius.toPx()
        val trackHeight = 16.dp.toPx()
        drawRoundRect(
            brush = Brush.horizontalGradient(trackColors, startX = inset, endX = size.width - inset),
            topLeft = Offset(inset - trackHeight / 2, center.y - trackHeight / 2),
            size = Size(size.width - 2 * inset + trackHeight, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2),
        )
        val thumb = Offset(inset + (value - valueRange.start) / span * (size.width - 2 * inset), center.y)
        drawCircle(Color.White, radius = inset, center = thumb)
        drawCircle(outline, radius = inset, center = thumb, style = Stroke(1.dp.toPx()))
        drawCircle(thumbColor, radius = inset - 3.dp.toPx(), center = thumb)
    }
}
