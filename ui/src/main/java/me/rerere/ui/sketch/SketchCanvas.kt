package me.rerere.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.withTranslation
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.roundToInt

/**
 * 画纸：跟着手指或触控笔落笔，并把 [state] 里的笔画和文字画出来。画纸按自己的比例放进可用区域的正中。
 *
 * 画笔交给 Ink：正在画的一笔由它的落笔层低延迟地画出来；画完再交回来，和橡皮、底图一起画在下面的画布上。
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
            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .aspectRatio(paper.width / paper.height)
                    .onSizeChanged { canvasSize = it }
                    .clip(MaterialTheme.shapes.medium)
                    // 贴着屏幕边缘起笔时不要被当成系统的返回手势
                    .systemGestureExclusion()
                    .pointerInput(state, paper) {
                        awaitEachGesture {
                            // 抢在落笔层前面看到按下
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // 落笔说明颜色调好了
                            state.pickingColor = false
                            // 画笔的一笔由落笔层接手，这里管橡皮、文字和选择
                            if (state.tool == SketchTool.Brush) return@awaitEachGesture
                            down.consume()
                            val scale = size.width / paper.width
                            // 画纸坐标的原点在正中
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if (state.tool == SketchTool.Text) {
                                // 手指抬起的地方就是文字的位置，划出画纸或者被打断就不算
                                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                                up.consume()
                                state.beginText((up.position - center) / scale, state.width.toPx() / scale)
                                return@awaitEachGesture
                            }
                            if (state.tool == SketchTool.Select) {
                                selectGesture(state, down, scale) { (it - center) / scale }
                                return@awaitEachGesture
                            }
                            val line = state.erase((down.position - center) / scale, state.width.toPx() / scale)
                            try {
                                // 只跟随落下的那根手指，其余的忽略
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.firstOrNull { it.id == down.id }
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
                val renderer = remember { CanvasStrokeRenderer.create() }
                val selectionColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    state.revision
                    drawSketch(state, size.width / paper.width, renderer)
                    drawSelection(state, size.width / paper.width, selectionColor)
                }
                if (canvasSize != IntSize.Zero) {
                    val scale = canvasSize.width / paper.width
                    val density = LocalDensity.current
                    val brush = remember(state.brush, state.color, state.width, scale, density) {
                        state.brush.create(state.color, with(density) { state.width.toPx() } / scale)
                    }
                    // 屏幕上的位置换成画纸坐标：先把原点挪到正中，再除掉缩放
                    val toPaper = remember(canvasSize, scale) {
                        Matrix().apply {
                            scale(1 / scale, 1 / scale)
                            translate(-canvasSize.width / 2f, -canvasSize.height / 2f)
                        }
                    }
                    // 不在用画笔时不出墨。落笔层本身留着，来回切换不用重新初始化
                    val currentBrush by rememberUpdatedState(if (state.tool == SketchTool.Brush) brush else null)
                    InProgressStrokes(
                        defaultBrush = currentBrush,
                        // 落笔层拿到的取笔刷的函数不会跟着重组更新，换了颜色和粗细要让它每次落笔时来读最新的
                        nextBrush = { currentBrush },
                        pointerEventToWorldTransform = toPaper,
                        onStrokesFinished = state::draw,
                    )
                }
            }
        }
    }
}

private val LayerPaint = Paint()

// 屏幕上显示和导出图片用的是同一套绘制，[scale] 是画纸坐标到目标像素的比例
internal fun DrawScope.drawSketch(state: SketchState, scale: Float, renderer: CanvasStrokeRenderer) {
    drawRect(SketchDefaults.PaperColor)
    state.background?.let {
        drawImage(image = it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
    val steps = state.steps
    // 清空之前的笔画已经看不到了
    val first = steps.indexOfLast { it is SketchStep.Clear } + 1
    val placements = state.placements()
    // Ink 按最终的缩放来决定笔画边缘画得多细，画布上已有的变换它不会自己去读
    val strokeTransform = android.graphics.Matrix()
    // 笔画单独画在一层上：橡皮擦掉的只是这一层，下面的画纸和底图不受影响
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), LayerPaint) }
    translate(size.width / 2, size.height / 2) {
        scale(scale, pivot = Offset.Zero) {
            for (index in first until steps.size) {
                when (val step = steps[index]) {
                    is SketchStep.Item -> {
                        val placement = placements[step] ?: continue
                        translate(placement.offset.x, placement.offset.y) {
                            scale(placement.scale, pivot = Offset.Zero) {
                                drawIntoCanvas {
                                    when (step) {
                                        is SketchStep.Ink -> {
                                            strokeTransform.setScale(scale * placement.scale, scale * placement.scale)
                                            renderer.draw(it.nativeCanvas, step.stroke, strokeTransform)
                                        }

                                        is SketchStep.Text -> it.nativeCanvas.withTranslation(
                                            step.position.x,
                                            step.position.y,
                                        ) { step.layout.draw(this) }
                                    }
                                }
                            }
                        }
                    }

                    is SketchStep.Erase -> if (step.moved) {
                        drawPath(
                            path = step.path,
                            color = Color.Black,
                            style = Stroke(width = step.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            blendMode = BlendMode.Clear,
                        )
                    } else {
                        drawCircle(
                            color = Color.Black,
                            radius = step.width / 2,
                            center = step.start,
                            blendMode = BlendMode.Clear,
                        )
                    }

                    is SketchStep.Transform, SketchStep.Clear -> {}
                }
            }
        }
    }
    drawIntoCanvas { it.restore() }
}
