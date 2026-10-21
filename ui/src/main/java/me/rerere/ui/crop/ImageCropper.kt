package me.rerere.ui.crop

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val FramePadding = 24.dp
private val TouchRadius = 24.dp
private val MinFrameSize = 64.dp
private val HandleLength = 20.dp
private val HandleWidth = 3.dp

private val ScrimColor = Color.Black.copy(alpha = 0.6f)
private val LineColor = Color.White
private val ShadowColor = Color.Black.copy(alpha = 0.3f)

/**
 * 裁剪画布：画图片、框外的遮罩和框，处理拖动图片、双指缩放和拖框的边角。
 */
@Composable
internal fun ImageCropper(
    state: ImageCropState,
    refitSpec: AnimationSpec<Float>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentRefitSpec by rememberUpdatedState(refitSpec)

    Canvas(
        modifier = modifier
            // 放大后的图片会画到画布外面，盖住顶栏和底部的比例按钮
            .clipToBounds()
            .onSizeChanged { size ->
                with(density) {
                    val padding = FramePadding.toPx()
                    state.layout(
                        area = Rect(padding, padding, size.width - padding, size.height - padding),
                        minFrameSize = MinFrameSize.toPx(),
                    )
                }
            }
            .excludeHandlesFromSystemGestures(state, with(density) { TouchRadius.toPx() })
            .pointerInput(state) {
                val touchRadius = TouchRadius.toPx()
                var refitJob: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 上一次松手后的回正动画还没放完时，按下就停在当前的位置
                    refitJob?.cancel()
                    state.interacting = true
                    val handle = state.handleAt(down.position, touchRadius)
                    if (handle != null) {
                        val start = state.frame
                        down.consume()
                        drag(down.id) { change ->
                            state.resize(handle, start, change.position - down.position)
                            change.consume()
                        }
                        state.interacting = false
                        refitJob = scope.launch { state.animateRefit(currentRefitSpec) }
                    } else {
                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (!canceled) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (centroid.isSpecified && (zoom != 1f || pan != Offset.Zero)) {
                                    state.transform(centroid, pan, zoom)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (!canceled && event.changes.any { it.pressed })
                        state.interacting = false
                    }
                }
            },
    ) {
        if (!state.isReady) return@Canvas
        drawCropImage(state)
        drawScrim(state)
        drawFrame(state)
    }
}

// 框贴着屏幕左右边缘时，拖边角会被当成系统的返回手势。一个 modifier 只能排除一块，每个边角各用一个
private fun Modifier.excludeHandlesFromSystemGestures(state: ImageCropState, touchRadius: Float): Modifier =
    (0 until CropHandle.entries.size).fold(this) { modifier, index ->
        modifier.systemGestureExclusion { state.handleBounds(touchRadius).getOrElse(index) { Rect.Zero } }
    }

private fun DrawScope.drawCropImage(state: ImageCropState) {
    val image = state.imageSize
    val offset = state.offset
    val scale = state.scale
    withTransform({
        translate(offset.x, offset.y)
        scale(scale, scale, pivot = Offset.Zero)
        // 绕图片中心转，转完之后左上角还在原点
        translate(image.width / 2, image.height / 2)
        rotate(90f * state.quarterTurns, pivot = Offset.Zero)
        translate(-state.sourceWidth / 2f, -state.sourceHeight / 2f)
    }) {
        drawImage(
            image = state.preview,
            dstSize = IntSize(state.sourceWidth, state.sourceHeight),
            filterQuality = FilterQuality.Medium,
        )
    }
}

// 只盖住图片上框外的部分，图片外面本来就是背景色
private fun DrawScope.drawScrim(state: ImageCropState) {
    val visible = state.imageRect.intersect(Rect(Offset.Zero, size))
    if (visible.isEmpty) return
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(visible)
        addRect(state.frame)
    }
    drawPath(path, ScrimColor)
}

private fun DrawScope.drawFrame(state: ImageCropState) {
    val frame = state.frame
    val line = 1.dp.toPx()
    if (state.interacting) {
        val thirdWidth = frame.width / 3
        val thirdHeight = frame.height / 3
        for (i in 1..2) {
            val x = frame.left + thirdWidth * i
            val y = frame.top + thirdHeight * i
            drawLine(LineColor.copy(alpha = 0.6f), Offset(x, frame.top), Offset(x, frame.bottom), line)
            drawLine(LineColor.copy(alpha = 0.6f), Offset(frame.left, y), Offset(frame.right, y), line)
        }
    }
    drawRect(LineColor, topLeft = frame.topLeft, size = frame.size, style = Stroke(line))

    // 边角压在框线外侧，底下垫一层浅阴影，浅色图片上也看得清
    val width = HandleWidth.toPx()
    val length = HandleLength.toPx().coerceAtMost(minOf(frame.width, frame.height) / 2)
    val outset = width / 2
    val outer = frame.inflate(outset)
    val segments = buildList {
        // 四个角各两段
        add(outer.topLeft to outer.topLeft.copy(x = outer.left + length))
        add(outer.topLeft to outer.topLeft.copy(y = outer.top + length))
        add(outer.topRight to outer.topRight.copy(x = outer.right - length))
        add(outer.topRight to outer.topRight.copy(y = outer.top + length))
        add(outer.bottomRight to outer.bottomRight.copy(x = outer.right - length))
        add(outer.bottomRight to outer.bottomRight.copy(y = outer.bottom - length))
        add(outer.bottomLeft to outer.bottomLeft.copy(x = outer.left + length))
        add(outer.bottomLeft to outer.bottomLeft.copy(y = outer.bottom - length))
        // 自由比例时四条边的中间也能拖
        if (state.ratio == null) {
            val center = outer.center
            add(Offset(center.x - length / 2, outer.top) to Offset(center.x + length / 2, outer.top))
            add(Offset(center.x - length / 2, outer.bottom) to Offset(center.x + length / 2, outer.bottom))
            add(Offset(outer.left, center.y - length / 2) to Offset(outer.left, center.y + length / 2))
            add(Offset(outer.right, center.y - length / 2) to Offset(outer.right, center.y + length / 2))
        }
    }
    segments.forEach { (start, end) ->
        drawLine(ShadowColor, start, end, width + 2.dp.toPx(), cap = StrokeCap.Round)
    }
    segments.forEach { (start, end) ->
        drawLine(LineColor, start, end, width, cap = StrokeCap.Round)
    }
}
