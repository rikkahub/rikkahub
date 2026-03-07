/*
 * Copyright 2023 Calvin Liang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.rerere.rikkahub.ui.components.ai.modelreorderable

// Forked from https://github.com/Calvin-LL/Reorderable tree v3.0.0 on 2026-03-07
// Local changes stay scoped to favorite model sorting

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope

@Composable
fun rememberReorderableLazyListState(
    lazyListState: LazyListState,
    scrollThresholdPadding: PaddingValues = PaddingValues(0.dp),
    scrollThreshold: androidx.compose.ui.unit.Dp = ReorderableLazyCollectionDefaults.ScrollThreshold,
    scroller: Scroller = rememberScroller(
        scrollableState = lazyListState,
        pixelPerSecond = lazyListState.layoutInfo.mainAxisViewportSize * ScrollAmountMultiplier / 0.1f,
    ),
    onMove: suspend CoroutineScope.(from: LazyListItemInfo, to: LazyListItemInfo) -> Unit,
): ReorderableLazyListState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollThresholdPx = with(density) { scrollThreshold.toPx() }
    val scope = rememberCoroutineScope()
    val onMoveState = rememberUpdatedState(onMove)
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val absoluteScrollThresholdPadding = AbsolutePixelPadding.fromPaddingValues(scrollThresholdPadding)
    val orientation by derivedStateOf { lazyListState.layoutInfo.orientation }

    return remember(
        scope,
        lazyListState,
        scrollThreshold,
        scrollThresholdPadding,
        scroller,
        orientation,
    ) {
        ReorderableLazyListState(
            state = lazyListState,
            scope = scope,
            onMoveState = onMoveState,
            scrollThreshold = scrollThresholdPx,
            scrollThresholdPadding = absoluteScrollThresholdPadding,
            scroller = scroller,
            layoutDirection = layoutDirection,
            shouldItemMove = when (orientation) {
                Orientation.Vertical -> { draggingItem, item ->
                    item.center.y in draggingItem.top..<draggingItem.bottom
                }

                Orientation.Horizontal -> { draggingItem, item ->
                    item.center.x in draggingItem.left..<draggingItem.right
                }
            },
        )
    }
}

private val LazyListLayoutInfo.mainAxisViewportSize: Int
    get() = when (orientation) {
        Orientation.Vertical -> viewportSize.height
        Orientation.Horizontal -> viewportSize.width
    }

private fun LazyListItemInfo.toLazyCollectionItemInfo(orientation: Orientation) =
    object : LazyCollectionItemInfo<LazyListItemInfo> {
        override val index: Int
            get() = this@toLazyCollectionItemInfo.index
        override val key: Any
            get() = this@toLazyCollectionItemInfo.key
        override val offset: IntOffset
            get() = IntOffset.fromAxis(orientation, this@toLazyCollectionItemInfo.offset)
        override val size: IntSize
            get() = IntSize.fromAxis(orientation, this@toLazyCollectionItemInfo.size)
        override val data: LazyListItemInfo
            get() = this@toLazyCollectionItemInfo
    }

private fun LazyListLayoutInfo.toLazyCollectionLayoutInfo() =
    object : LazyCollectionLayoutInfo<LazyListItemInfo> {
        override val visibleItemsInfo: List<LazyCollectionItemInfo<LazyListItemInfo>>
            get() = this@toLazyCollectionLayoutInfo.visibleItemsInfo.map {
                it.toLazyCollectionItemInfo(orientation)
            }
        override val viewportSize: IntSize
            get() = this@toLazyCollectionLayoutInfo.viewportSize
        override val orientation: Orientation
            get() = this@toLazyCollectionLayoutInfo.orientation
        override val reverseLayout: Boolean
            get() = this@toLazyCollectionLayoutInfo.reverseLayout
        override val beforeContentPadding: Int
            get() = this@toLazyCollectionLayoutInfo.beforeContentPadding
    }

private fun LazyListState.toLazyCollectionState() =
    object : LazyCollectionState<LazyListItemInfo> {
        override val firstVisibleItemIndex: Int
            get() = this@toLazyCollectionState.firstVisibleItemIndex
        override val firstVisibleItemScrollOffset: Int
            get() = this@toLazyCollectionState.firstVisibleItemScrollOffset
        override val layoutInfo: LazyCollectionLayoutInfo<LazyListItemInfo>
            get() = this@toLazyCollectionState.layoutInfo.toLazyCollectionLayoutInfo()

        override suspend fun animateScrollBy(value: Float, animationSpec: AnimationSpec<Float>) =
            this@toLazyCollectionState.animateScrollBy(value, animationSpec)

        override suspend fun requestScrollToItem(index: Int, scrollOffset: Int) =
            this@toLazyCollectionState.requestScrollToItem(index, scrollOffset)
    }

@Stable
class ReorderableLazyListState internal constructor(
    state: LazyListState,
    scope: CoroutineScope,
    onMoveState: State<suspend CoroutineScope.(from: LazyListItemInfo, to: LazyListItemInfo) -> Unit>,
    scrollThreshold: Float,
    scrollThresholdPadding: AbsolutePixelPadding,
    scroller: Scroller,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    shouldItemMove: (draggingItem: Rect, item: Rect) -> Boolean,
) : ReorderableLazyCollectionState<LazyListItemInfo>(
    state = state.toLazyCollectionState(),
    scope = scope,
    onMoveState = onMoveState,
    scrollThreshold = scrollThreshold,
    scrollThresholdPadding = scrollThresholdPadding,
    scroller = scroller,
    layoutDirection = layoutDirection,
    shouldItemMove = shouldItemMove,
)

@Composable
fun LazyItemScope.ReorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animateItemModifier: Modifier = Modifier.animateItem(),
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean) -> Unit,
) {
    val orientation by derivedStateOf { state.orientation }
    val dragging by state.isItemDragging(key)
    val offsetModifier = when {
        dragging -> Modifier
            .zIndex(1f)
            .then(
                when (orientation) {
                    Orientation.Vertical -> Modifier.graphicsLayer {
                        translationY = state.draggingItemOffset.y
                    }

                    Orientation.Horizontal -> Modifier.graphicsLayer {
                        translationX = state.draggingItemOffset.x
                    }
                }
            )

        key == state.settlingItemKey -> Modifier
            .zIndex(1f)
            .then(
                when (orientation) {
                    Orientation.Vertical -> Modifier.graphicsLayer {
                        translationY = state.settlingItemOffset.value.y
                    }

                    Orientation.Horizontal -> Modifier.graphicsLayer {
                        translationX = state.settlingItemOffset.value.x
                    }
                }
            )

        else -> animateItemModifier
    }

    ReorderableCollectionItem(
        state = state,
        key = key,
        modifier = modifier.then(offsetModifier),
        enabled = enabled,
        dragging = dragging,
        content = content,
    )
}
