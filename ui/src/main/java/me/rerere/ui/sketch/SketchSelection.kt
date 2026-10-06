package me.rerere.ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.dp

// 手指离四角的控制点多近算是按住了它
private val HandleReach = 24.dp

// 一次拖动最多缩放到原来的多少倍
private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 10f

/** 对一个笔画或一段文字的摆放：先放大 [scale] 倍，再挪 [offset]。 */
internal data class SketchPlacement(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    /** 在这次摆放之后接着再做 [next]。 */
    fun then(next: SketchPlacement) = SketchPlacement(scale * next.scale, offset * next.scale + next.offset)

    fun apply(point: Offset) = point * scale + offset

    fun apply(rect: Rect) = Rect(apply(rect.topLeft), apply(rect.bottomRight))

    /** 摆放之后的 [rect] 在摆放之前是哪一块。 */
    fun invert(rect: Rect) = Rect((rect.topLeft - offset) / scale, (rect.bottomRight - offset) / scale)

    companion object {
        val None = SketchPlacement()

        fun move(by: Offset) = SketchPlacement(offset = by)

        /** 以 [around] 为不动点放大 [by] 倍。 */
        fun scale(by: Float, around: Offset) = SketchPlacement(by, around * (1 - by))
    }
}

private val Rect.corners: List<Offset> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

// 跟着 [down] 的那根手指直到它抬起，每动一下报一次它在画纸上的位置
private suspend fun AwaitPointerEventScope.drag(
    down: PointerInputChange,
    toPaper: (Offset) -> Offset,
    onMove: (Offset) -> Unit,
) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
        if (change == null) break
        change.consume()
        if (!change.pressed) break
        onMove(toPaper(change.position))
    }
}

/**
 * 选择工具的一次手势，从 [down] 按下到松手：
 * 按在选中范围的角上是缩放，按在范围里面是挪动，按在别处是重新选——划一下拉框，点一下只选点到的那个。
 *
 * @param scale 画纸坐标到屏幕像素的比例
 * @param toPaper 把屏幕上的位置换成画纸坐标
 */
internal suspend fun AwaitPointerEventScope.selectGesture(
    state: SketchState,
    down: PointerInputChange,
    scale: Float,
    toPaper: (Offset) -> Offset,
) {
    val start = toPaper(down.position)
    val reach = HandleReach.toPx() / scale
    val bounds = state.selectionBounds
    val corner = bounds?.corners
        ?.filter { (it - start).getDistance() <= reach }
        ?.minByOrNull { (it - start).getDistance() }
    try {
        when {
            bounds != null && corner != null -> {
                // 对角不动，按手指到对角的距离变了多少倍来缩放
                val pivot = bounds.center * 2f - corner
                val from = (start - pivot).getDistance()
                drag(down, toPaper) { point ->
                    if (from > 0f) {
                        val by = ((point - pivot).getDistance() / from).coerceIn(MIN_SCALE, MAX_SCALE)
                        state.moving = SketchPlacement.scale(by, pivot)
                    }
                }
            }

            bounds != null && bounds.contains(start) -> drag(down, toPaper) {
                state.moving = SketchPlacement.move(it - start)
            }

            else -> {
                state.deselect()
                drag(down, toPaper) { point ->
                    // 手指稍微抖一下不算拉框
                    if (state.marquee != null || (point - start).getDistance() * scale > viewConfiguration.touchSlop) {
                        state.marquee = Rect(
                            minOf(start.x, point.x),
                            minOf(start.y, point.y),
                            maxOf(start.x, point.x),
                            maxOf(start.y, point.y),
                        )
                    }
                }
                val marquee = state.marquee
                state.select(marquee ?: Rect(center = start, radius = reach / 2), topmost = marquee == null)
            }
        }
    } finally {
        // 手势被系统打断时也要收尾
        state.marquee = null
        state.settle()
    }
}

/** 选框和选中范围的边框、四角的控制点。只画在屏幕上，不会进到导出的图片里。 */
internal fun DrawScope.drawSelection(state: SketchState, scale: Float, color: Color) {
    val marquee = state.marquee
    val bounds = state.selectionBounds
    if (marquee == null && bounds == null) return
    translate(size.width / 2, size.height / 2) {
        scale(scale, pivot = Offset.Zero) {
            // 这里的单位是画纸坐标，线宽和圆点要除掉缩放才是屏幕上想要的大小
            val outline = Stroke(
                width = 1.5.dp.toPx() / scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / scale, 4.dp.toPx() / scale)),
            )
            if (marquee != null) {
                drawRect(color.copy(alpha = 0.12f), marquee.topLeft, marquee.size)
                drawRect(color, marquee.topLeft, marquee.size, style = outline)
            }
            if (bounds != null) {
                drawRect(color, bounds.topLeft, bounds.size, style = outline)
                val radius = 5.dp.toPx() / scale
                bounds.corners.forEach {
                    drawCircle(Color.White, radius, it)
                    drawCircle(color, radius, it, style = Stroke(2.dp.toPx() / scale))
                }
            }
        }
    }
}
