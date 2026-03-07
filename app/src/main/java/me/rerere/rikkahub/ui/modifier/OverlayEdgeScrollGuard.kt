package me.rerere.rikkahub.ui.modifier

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

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
): Modifier = composed {
    val canScrollBackwardState by rememberUpdatedState(canScrollBackward)
    val canScrollForwardState by rememberUpdatedState(canScrollForward)
    // 列表到边界后把剩余手势留在内层
    // 避免外层 sheet 或 drawer 接手时先顿一下
    val connection = remember(orientation) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return consumeScrollRemainderAtEdge(
                    consumed = consumed.axisValue(orientation),
                    available = available.axisValue(orientation),
                    orientation = orientation,
                    canScrollBackward = canScrollBackwardState(),
                    canScrollForward = canScrollForwardState()
                )
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return consumeFlingRemainderAtEdge(
                    consumed = consumed.axisValue(orientation),
                    available = available.axisValue(orientation),
                    orientation = orientation,
                    canScrollBackward = canScrollBackwardState(),
                    canScrollForward = canScrollForwardState()
                )
            }
        }
    }
    nestedScroll(connection)
}

private fun consumeScrollRemainderAtEdge(
    consumed: Float,
    available: Float,
    orientation: Orientation,
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Offset {
    if (abs(consumed) <= 0.5f) return Offset.Zero
    if (!shouldConsumeAtEdge(available, canScrollBackward, canScrollForward)) return Offset.Zero
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
    if (abs(consumed) <= 0.5f) return Velocity.Zero
    if (!shouldConsumeAtEdge(available, canScrollBackward, canScrollForward)) return Velocity.Zero
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
    if (abs(available) <= 0.5f) return false
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
