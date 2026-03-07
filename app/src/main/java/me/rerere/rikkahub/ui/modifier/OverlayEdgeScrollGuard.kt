package me.rerere.rikkahub.ui.modifier

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

// 列表到边界时还会有少量 scroll 和 fling 残量
// 吃掉残量避免外层 sheet 或 drawer 接到手势后顿一下
private const val EdgeRemainderThresholdPx = 0.5f

fun Modifier.overlayEdgeScrollGuard(
    state: LazyListState,
    orientation: Orientation = Orientation.Vertical
): Modifier = overlayEdgeScrollGuard(
    orientation = orientation,
    canScrollBackward = { state.canScrollBackward },
    canScrollForward = { state.canScrollForward }
)

fun Modifier.overlayEdgeScrollGuard(
    state: LazyGridState,
    orientation: Orientation = Orientation.Vertical
): Modifier = overlayEdgeScrollGuard(
    orientation = orientation,
    canScrollBackward = { state.canScrollBackward },
    canScrollForward = { state.canScrollForward }
)

fun Modifier.overlayEdgeScrollGuard(
    state: ScrollState,
    orientation: Orientation = Orientation.Vertical
): Modifier = overlayEdgeScrollGuard(
    orientation = orientation,
    canScrollBackward = { state.value > 0 },
    canScrollForward = { state.value < state.maxValue }
)

private fun Modifier.overlayEdgeScrollGuard(
    orientation: Orientation,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean
): Modifier = this.then(
    OverlayEdgeScrollGuardElement(
        orientation = orientation,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )
)

private data class OverlayEdgeScrollGuardElement(
    val orientation: Orientation,
    val canScrollBackward: () -> Boolean,
    val canScrollForward: () -> Boolean,
) : ModifierNodeElement<OverlayEdgeScrollGuardNode>() {
    override fun create(): OverlayEdgeScrollGuardNode {
        return OverlayEdgeScrollGuardNode(
            orientation = orientation,
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
        )
    }

    override fun update(node: OverlayEdgeScrollGuardNode) {
        node.update(
            orientation = orientation,
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "overlayEdgeScrollGuard"
        properties["orientation"] = orientation
    }
}

private class OverlayEdgeScrollGuardNode(
    private var orientation: Orientation,
    private var canScrollBackward: () -> Boolean,
    private var canScrollForward: () -> Boolean,
) : DelegatingNode() {
    private val connection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            // 只在内层到边界时消费 available
            // 避免残量交给外层 overlay
            return consumeScrollRemainderAtEdge(
                consumed = consumed.axisValue(orientation),
                available = available.axisValue(orientation),
                orientation = orientation,
                canScrollBackward = canScrollBackward(),
                canScrollForward = canScrollForward()
            )
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // fling 同理 只吃边界残量
            return consumeFlingRemainderAtEdge(
                consumed = consumed.axisValue(orientation),
                available = available.axisValue(orientation),
                orientation = orientation,
                canScrollBackward = canScrollBackward(),
                canScrollForward = canScrollForward()
            )
        }
    }

    @Suppress("unused")
    private val nestedScrollNode = delegate(
        nestedScrollModifierNode(
            connection = connection,
            dispatcher = null,
        )
    )

    fun update(
        orientation: Orientation,
        canScrollBackward: () -> Boolean,
        canScrollForward: () -> Boolean,
    ) {
        this.orientation = orientation
        this.canScrollBackward = canScrollBackward
        this.canScrollForward = canScrollForward
    }
}

private fun consumeScrollRemainderAtEdge(
    consumed: Float,
    available: Float,
    orientation: Orientation,
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Offset {
    if (abs(consumed) <= EdgeRemainderThresholdPx) return Offset.Zero
    if (!shouldConsumeAtEdge(available, canScrollBackward, canScrollForward)) return Offset.Zero
    // 到边界后把剩余手势吃掉
    return when (orientation) {
        Orientation.Vertical -> Offset(x = 0f, y = available)
        Orientation.Horizontal -> Offset(x = available, y = 0f)
    }
}

private fun consumeFlingRemainderAtEdge(
    consumed: Float,
    available: Float,
    orientation: Orientation,
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Velocity {
    if (abs(consumed) <= EdgeRemainderThresholdPx) return Velocity.Zero
    if (!shouldConsumeAtEdge(available, canScrollBackward, canScrollForward)) return Velocity.Zero
    // 到边界后把剩余惯性吃掉
    return when (orientation) {
        Orientation.Vertical -> Velocity(x = 0f, y = available)
        Orientation.Horizontal -> Velocity(x = available, y = 0f)
    }
}

private fun shouldConsumeAtEdge(
    available: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Boolean {
    if (abs(available) <= EdgeRemainderThresholdPx) return false
    return (available > 0f && !canScrollBackward) || (available < 0f && !canScrollForward)
}

private fun Offset.axisValue(orientation: Orientation): Float {
    return when (orientation) {
        Orientation.Vertical -> y
        Orientation.Horizontal -> x
    }
}

private fun Velocity.axisValue(orientation: Orientation): Float {
    return when (orientation) {
        Orientation.Vertical -> y
        Orientation.Horizontal -> x
    }
}
