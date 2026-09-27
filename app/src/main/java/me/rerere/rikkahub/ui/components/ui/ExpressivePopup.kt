package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Popup 相对锚点优先出现的一侧, 空间不足时自动翻转 */
enum class ExpressivePopupPlacement { Above, Below }

/** Popup 与锚点在水平方向上的对齐方式, Start/End 跟随布局方向 */
enum class ExpressivePopupAlignment { Start, Center, End }

/**
 * MD3 Expressive 风格的锚定 Popup
 *
 * 默认外观对齐 Expressive 分组菜单 (standalone group) 的 tokens。
 * 放在锚点组件的同一父布局中 (通常用 Box 包裹锚点), 以父布局边界作为锚点定位。
 * 进入时从锚点方向以空间弹簧缩放展开, 退出时使用无回弹的效果弹簧收起,
 * 退出动画结束前 Popup 会一直保持存活。
 */
@Composable
fun ExpressivePopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placement: ExpressivePopupPlacement = ExpressivePopupPlacement.Below,
    alignment: ExpressivePopupAlignment = ExpressivePopupAlignment.Start,
    anchorGap: Dp = 8.dp,
    windowMargin: Dp = 16.dp,
    shape: Shape = MenuDefaults.standaloneGroupShape,
    containerColor: Color = MenuDefaults.groupStandardContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded

    if (!expandedState.currentState && !expandedState.targetState) return

    val density = LocalDensity.current
    val transformOrigin = remember { mutableStateOf(TransformOrigin.Center) }
    val positionProvider = remember(placement, alignment, anchorGap, windowMargin, density) {
        ExpressivePopupPositionProvider(
            placement = placement,
            alignment = alignment,
            anchorGap = with(density) { anchorGap.roundToPx() },
            windowMargin = with(density) { windowMargin.roundToPx() },
            onPositionCalculated = { transformOrigin.value = it },
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        val motionScheme = MaterialTheme.motionScheme
        val transition = rememberTransition(expandedState, label = "ExpressivePopup")
        val scale by transition.animateFloat(
            transitionSpec = {
                if (targetState) motionScheme.fastSpatialSpec() else motionScheme.fastEffectsSpec()
            },
            label = "scale",
        ) { if (it) 1f else 0.8f }
        val alpha by transition.animateFloat(
            transitionSpec = { motionScheme.fastEffectsSpec() },
            label = "alpha",
        ) { if (it) 1f else 0f }

        // 结构同官方 DropdownMenuContent, 额外设置 outsets:
        // alpha < 1 时本层会走离屏合成, 离屏缓冲默认只有本层大小, 会把 Surface 投在边界外的阴影裁掉,
        // 导致动画结束 (alpha 回到 1) 的瞬间阴影突然出现
        Surface(
            modifier = modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.transformOrigin = transformOrigin.value
                outsets = LayerOutsets(shadowElevation * 2)
            },
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}

private class ExpressivePopupPositionProvider(
    private val placement: ExpressivePopupPlacement,
    private val alignment: ExpressivePopupAlignment,
    private val anchorGap: Int,
    private val windowMargin: Int,
    private val onPositionCalculated: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height

        val isLtr = layoutDirection == LayoutDirection.Ltr
        val preferredX = when (alignment) {
            ExpressivePopupAlignment.Start -> if (isLtr) anchorBounds.left else anchorBounds.right - width
            ExpressivePopupAlignment.End -> if (isLtr) anchorBounds.right - width else anchorBounds.left
            ExpressivePopupAlignment.Center -> anchorBounds.center.x - width / 2
        }
        val maxX = (windowSize.width - width - windowMargin).coerceAtLeast(windowMargin)
        val x = preferredX.coerceIn(windowMargin, maxX)

        val aboveY = anchorBounds.top - anchorGap - height
        val belowY = anchorBounds.bottom + anchorGap
        val fitsAbove = aboveY >= windowMargin
        val fitsBelow = belowY + height <= windowSize.height - windowMargin
        val showAbove = when (placement) {
            ExpressivePopupPlacement.Above -> fitsAbove || !fitsBelow && anchorBounds.top > windowSize.height - anchorBounds.bottom
            ExpressivePopupPlacement.Below -> !fitsBelow && (fitsAbove || anchorBounds.top > windowSize.height - anchorBounds.bottom)
        }
        val maxY = (windowSize.height - height - windowMargin).coerceAtLeast(windowMargin)
        val y = (if (showAbove) aboveY else belowY).coerceIn(windowMargin, maxY)

        // 从锚点中心所在的一侧展开
        val pivotX = if (width > 0) {
            ((anchorBounds.center.x - x).toFloat() / width).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        onPositionCalculated(TransformOrigin(pivotX, if (showAbove) 1f else 0f))

        return IntOffset(x, y)
    }
}
