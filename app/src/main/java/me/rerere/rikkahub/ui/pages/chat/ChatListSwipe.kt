package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

private val PreviewSwipeSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

/**
 * 概览页露出的比例，0 是聊天页，1 是概览页。
 * 外部切换 [previewMode]（如顶栏按钮、点击跳转）时动画过去；手势拖动时由 [previewSwipeGesture] 直接驱动。
 */
@Composable
internal fun rememberPreviewSwipeProgress(previewMode: Boolean): Animatable<Float, *> {
    val progress = remember { Animatable(if (previewMode) 1f else 0f) }
    LaunchedEffect(previewMode) {
        val target = if (previewMode) 1f else 0f
        // 松手时手势已经带着速度动画到目标了，这里不要再打断它
        if (progress.targetValue != target) {
            progress.animateTo(target, PreviewSwipeSpec)
        }
    }
    return progress
}

/**
 * 聊天页向左滑进入概览，概览页向右滑返回（RTL 下方向相反）。
 * 只在方向对的时候才接管手势，反方向留给侧边抽屉；横向滚动的子组件（代码块、表格）会先消费掉事件，不受影响。
 */
internal fun Modifier.previewSwipeGesture(
    progress: Animatable<Float, *>,
    scope: CoroutineScope,
    layoutDirection: LayoutDirection,
    canEnterPreview: () -> Boolean,
    isPreviewMode: () -> Boolean,
    onPreviewModeChange: (Boolean) -> Unit,
): Modifier = pointerInput(layoutDirection) {
    // 手指往左（LTR）是进入概览的方向
    val sign = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val flingThreshold = 400.dp.toPx()

    // 拖动进度自己记，不读 progress.value：snapTo 可能要等上一段动画取消才生效
    var dragProgress = 0f
    fun dragBy(dx: Float, width: Float) {
        dragProgress = (dragProgress - dx * sign / width).coerceIn(0f, 1f)
        val value = dragProgress
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value) }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startInPreview = isPreviewMode()
        // 空对话没什么可概览的
        if (!startInPreview && !canEnterPreview()) return@awaitEachGesture
        var slop = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            val towardsPreview = -over * sign > 0
            if (towardsPreview != startInPreview) {
                change.consume()
                slop = over
            }
        } ?: return@awaitEachGesture

        val width = size.width.toFloat().coerceAtLeast(1f)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(drag)
        dragProgress = progress.value
        dragBy(slop, width)

        // 接管后在 Initial 阶段就消费掉，避免斜着拖时消息列表同时纵向滚动、长按菜单误触
        var completed = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == drag.id } ?: break
            tracker.addPointerInputChange(change)
            if (change.changedToUpIgnoreConsumed()) {
                completed = true
                break
            }
            dragBy(change.positionChangeIgnoreConsumed().x, width)
            change.consume()
        }

        // 正值表示朝概览方向
        val velocity = -tracker.calculateVelocity().x * sign
        val target = when {
            !completed -> startInPreview
            abs(velocity) > flingThreshold -> velocity > 0
            else -> dragProgress > 0.5f
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.animateTo(
                targetValue = if (target) 1f else 0f,
                animationSpec = PreviewSwipeSpec,
                initialVelocity = velocity / width,
            )
        }
        if (target != isPreviewMode()) onPreviewModeChange(target)
    }
}
