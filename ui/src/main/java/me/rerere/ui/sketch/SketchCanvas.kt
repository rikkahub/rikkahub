package me.rerere.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt

/**
 * 画纸：跟着手指落笔，并把 [state] 里的笔画画出来。画纸按自己的比例放进可用区域的正中。
 */
@Composable
internal fun SketchCanvas(state: SketchState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.onSizeChanged {
            if (it.width > 0 && it.height > 0) state.fit(it.toSize())
        },
        contentAlignment = Alignment.Center,
    ) {
        val paper = state.paperSize
        if (paper.isSpecified) {
            Canvas(
                modifier = Modifier
                    .aspectRatio(paper.width / paper.height)
                    .clip(MaterialTheme.shapes.medium)
                    // 贴着屏幕边缘起笔时不要被当成系统的返回手势
                    .systemGestureExclusion()
                    .pointerInput(state, paper) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val scale = size.width / paper.width
                            // 画纸坐标的原点在正中
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val line = state.begin((down.position - center) / scale, state.width.toPx() / scale)
                            try {
                                // 只跟随落下的那根手指，其余的忽略
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    // 两次事件之间系统攒下的采样点也用上，快速划过时线条才不会有棱角
                                    change.historical.forEach { state.extend(line, (it.position - center) / scale) }
                                    state.extend(line, (change.position - center) / scale)
                                    change.consume()
                                }
                            } finally {
                                // 手势被系统打断时也要收尾
                                state.finish(line)
                            }
                        }
                    },
            ) {
                state.revision
                drawSketch(state, size.width / paper.width)
            }
        }
    }
}

private val LayerPaint = Paint()

// 屏幕上显示和导出图片用的是同一套绘制，[scale] 是画纸坐标到目标像素的比例
internal fun DrawScope.drawSketch(state: SketchState, scale: Float) {
    drawRect(SketchDefaults.PaperColor)
    state.background?.let {
        drawImage(image = it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
    val steps = state.steps
    // 清空之前的笔画已经看不到了
    val first = steps.indexOfLast { it is SketchStep.Clear } + 1
    // 笔画单独画在一层上：橡皮擦掉的只是这一层，下面的画纸和底图不受影响
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), LayerPaint) }
    translate(size.width / 2, size.height / 2) {
        scale(scale, pivot = Offset.Zero) {
            for (index in first until steps.size) {
                val line = steps[index] as? SketchStep.Line ?: continue
                val blendMode = if (line.erase) BlendMode.Clear else BlendMode.SrcOver
                if (line.moved) {
                    drawPath(
                        path = line.path,
                        color = line.color,
                        style = Stroke(width = line.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = blendMode,
                    )
                } else {
                    drawCircle(
                        color = line.color,
                        radius = line.width / 2,
                        center = line.start,
                        blendMode = blendMode,
                    )
                }
            }
        }
    }
    drawIntoCanvas { it.restore() }
}
