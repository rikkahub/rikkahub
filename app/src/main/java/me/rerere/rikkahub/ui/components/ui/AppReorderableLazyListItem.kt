@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableCollectionItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import kotlin.math.abs

enum class ReorderItemPlacementPolicy {
    LibraryDefault,
    ContentManaged
}

enum class ReorderItemReleaseSettlePolicy {
    LibraryDefault,
    AdaptivePreserved
}

private data class ReleaseSettleProfile(
    val stiffness: Float,
    val dampingRatio: Float
)

@Composable
fun LazyItemScope.AppReorderableLazyListItem(
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placementPolicy: ReorderItemPlacementPolicy = ReorderItemPlacementPolicy.LibraryDefault,
    releaseSettlePolicy: ReorderItemReleaseSettlePolicy = ReorderItemReleaseSettlePolicy.LibraryDefault,
    contentModifier: Modifier = Modifier,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean, itemModifier: Modifier) -> Unit
) {
    val itemPlacementModifier = when (placementPolicy) {
        ReorderItemPlacementPolicy.LibraryDefault -> Modifier.animateItem()
        ReorderItemPlacementPolicy.ContentManaged -> Modifier
    }
    val resolvedContentModifier = when (placementPolicy) {
        ReorderItemPlacementPolicy.LibraryDefault -> contentModifier
        ReorderItemPlacementPolicy.ContentManaged -> contentModifier.animateItem()
    }

    when (releaseSettlePolicy) {
        ReorderItemReleaseSettlePolicy.LibraryDefault -> {
            ReorderableItem(
                state = state,
                key = key,
                modifier = modifier,
                enabled = enabled,
                animateItemModifier = itemPlacementModifier
            ) { isDragging ->
                content(isDragging, resolvedContentModifier)
            }
        }

        ReorderItemReleaseSettlePolicy.AdaptivePreserved -> {
            AdaptivePreservedReorderableLazyListItem(
                state = state,
                key = key,
                modifier = modifier,
                enabled = enabled,
                itemPlacementModifier = itemPlacementModifier,
                contentModifier = resolvedContentModifier,
                content = content
            )
        }
    }
}

@Composable
private fun LazyItemScope.AdaptivePreservedReorderableLazyListItem(
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier,
    enabled: Boolean,
    itemPlacementModifier: Modifier,
    contentModifier: Modifier,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean, itemModifier: Modifier) -> Unit
) {
    val orientation = state.orientation
    val isDragging by state.isItemDragging(key)
    val previousDraggingItemKey = state.previousDraggingItemKey
    val releaseOffset = remember(key) { Animatable(Offset.Zero, Offset.VectorConverter) }

    var itemSize by remember(key) { mutableStateOf(IntSize.Zero) }
    var isReleaseSettling by remember(key) { mutableStateOf(false) }

    LaunchedEffect(isDragging, previousDraggingItemKey) {
        if (isDragging || previousDraggingItemKey != key) {
            isReleaseSettling = false
            releaseOffset.snapTo(Offset.Zero)
        }
    }

    LaunchedEffect(previousDraggingItemKey, isDragging, itemSize) {
        if (isDragging || previousDraggingItemKey != key) return@LaunchedEffect

        val startOffset = state.previousDraggingItemOffset.value
        val releaseDistancePx = mainAxisDistance(startOffset, orientation)
        if (releaseDistancePx <= 0.5f) {
            isReleaseSettling = false
            releaseOffset.snapTo(Offset.Zero)
            return@LaunchedEffect
        }

        val settleProfile = adaptiveReleaseSettleProfile(
            releaseDistancePx = releaseDistancePx,
            itemSize = itemSize,
            orientation = orientation
        )

        isReleaseSettling = true
        releaseOffset.snapTo(startOffset)
        releaseOffset.animateTo(
            targetValue = Offset.Zero,
            animationSpec = spring(
                dampingRatio = settleProfile.dampingRatio,
                stiffness = settleProfile.stiffness
            )
        )
        isReleaseSettling = false
    }

    val stateModifier = when {
        isDragging -> Modifier.mainAxisTranslation(
            orientation = orientation,
            offsetProvider = { state.draggingItemOffset }
        )

        isReleaseSettling -> Modifier.mainAxisTranslation(
            orientation = orientation,
            offsetProvider = { releaseOffset.value }
        )

        else -> itemPlacementModifier
    }

    ReorderableCollectionItem(
        state,
        key,
        modifier
            .onSizeChanged { itemSize = it }
            .then(stateModifier),
        enabled,
        isDragging
    ) { dragging ->
        content(dragging, contentModifier)
    }
}

private fun Modifier.mainAxisTranslation(
    orientation: Orientation,
    offsetProvider: () -> Offset
): Modifier {
    return zIndex(1f).graphicsLayer {
        val offset = offsetProvider()
        translationX = 0f
        translationY = 0f
        when (orientation) {
            Orientation.Vertical -> translationY = offset.y
            Orientation.Horizontal -> translationX = offset.x
        }
    }
}

private fun adaptiveReleaseSettleProfile(
    releaseDistancePx: Float,
    itemSize: IntSize,
    orientation: Orientation
): ReleaseSettleProfile {
    val itemMainAxisSizePx = when (orientation) {
        Orientation.Vertical -> itemSize.height
        Orientation.Horizontal -> itemSize.width
    }.coerceAtLeast(1)

    val normalizedDistance = (releaseDistancePx / itemMainAxisSizePx.toFloat()).coerceIn(0f, 1f)
    val stiffness = Spring.StiffnessMedium +
        (Spring.StiffnessHigh - Spring.StiffnessMedium) * normalizedDistance

    return ReleaseSettleProfile(
        stiffness = stiffness,
        dampingRatio = Spring.DampingRatioNoBouncy
    )
}

private fun mainAxisDistance(
    offset: Offset,
    orientation: Orientation
): Float {
    return when (orientation) {
        Orientation.Vertical -> abs(offset.y)
        Orientation.Horizontal -> abs(offset.x)
    }
}
