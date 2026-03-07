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
// 本地改动只收口到收藏模型排序
// 1. 只保留 LazyList 路径需要的最小实现
// 2. 释放阶段改成应用侧自适应 settle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

private const val ReleaseSettleThresholdPx = 0.5f

object ReorderableLazyCollectionDefaults {
    val ScrollThreshold = 48.dp
}

internal const val ScrollAmountMultiplier = 0.05f

internal data class AbsolutePixelPadding(
    val start: Float,
    val end: Float,
    val top: Float,
    val bottom: Float,
) {
    companion object {
        @Composable
        fun fromPaddingValues(paddingValues: PaddingValues): AbsolutePixelPadding {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current

            return AbsolutePixelPadding(
                start = with(density) { paddingValues.calculateStartPadding(layoutDirection).toPx() },
                end = with(density) { paddingValues.calculateEndPadding(layoutDirection).toPx() },
                top = with(density) { paddingValues.calculateTopPadding().toPx() },
                bottom = with(density) { paddingValues.calculateBottomPadding().toPx() },
            )
        }
    }
}

internal interface LazyCollectionItemInfo<out T> {
    val index: Int
    val key: Any
    val offset: IntOffset
    val size: IntSize
    val data: T

    val center: IntOffset
        get() = IntOffset(offset.x + size.width / 2, offset.y + size.height / 2)
}

internal data class CollectionScrollPadding(
    val start: Float,
    val end: Float,
) {
    companion object {
        fun fromAbsolutePixelPadding(
            orientation: Orientation,
            padding: AbsolutePixelPadding,
            reverseLayout: Boolean,
        ): CollectionScrollPadding {
            return when (orientation) {
                Orientation.Vertical -> CollectionScrollPadding(start = padding.top, end = padding.bottom)
                Orientation.Horizontal -> CollectionScrollPadding(start = padding.start, end = padding.end)
            }.let {
                if (reverseLayout) CollectionScrollPadding(start = it.end, end = it.start) else it
            }
        }
    }
}

internal data class ScrollAreaOffsets(
    val start: Float,
    val end: Float,
)

internal interface LazyCollectionLayoutInfo<out T> {
    val visibleItemsInfo: List<LazyCollectionItemInfo<T>>
    val viewportSize: IntSize
    val orientation: Orientation
    val reverseLayout: Boolean
    val beforeContentPadding: Int

    val mainAxisViewportSize: Int
        get() = when (orientation) {
            Orientation.Vertical -> viewportSize.height
            Orientation.Horizontal -> viewportSize.width
        }

    fun getScrollAreaOffsets(padding: AbsolutePixelPadding): ScrollAreaOffsets {
        val scrollPadding = CollectionScrollPadding.fromAbsolutePixelPadding(
            orientation = orientation,
            padding = padding,
            reverseLayout = reverseLayout,
        )
        val contentEndOffset = when (orientation) {
            Orientation.Vertical -> viewportSize.height
            Orientation.Horizontal -> viewportSize.width
        } - scrollPadding.end
        return ScrollAreaOffsets(
            start = scrollPadding.start,
            end = contentEndOffset,
        )
    }

    fun getItemsInContentArea(padding: AbsolutePixelPadding): List<LazyCollectionItemInfo<T>> {
        val (contentStartOffset, contentEndOffset) = getScrollAreaOffsets(padding)
        return when (orientation) {
            Orientation.Vertical -> visibleItemsInfo.filter { item ->
                item.offset.y >= contentStartOffset && item.offset.y + item.size.height <= contentEndOffset
            }

            Orientation.Horizontal -> visibleItemsInfo.filter { item ->
                item.offset.x >= contentStartOffset && item.offset.x + item.size.width <= contentEndOffset
            }
        }
    }
}

internal interface LazyCollectionState<out T> {
    val firstVisibleItemIndex: Int
    val firstVisibleItemScrollOffset: Int
    val layoutInfo: LazyCollectionLayoutInfo<T>

    suspend fun animateScrollBy(value: Float, animationSpec: androidx.compose.animation.core.AnimationSpec<Float> = spring()): Float
    suspend fun requestScrollToItem(index: Int, scrollOffset: Int)
}

interface ReorderableLazyCollectionStateInterface {
    val isAnyItemDragging: Boolean
}

@Stable
open class ReorderableLazyCollectionState<out T> internal constructor(
    private val state: LazyCollectionState<T>,
    private val scope: CoroutineScope,
    private val onMoveState: State<suspend CoroutineScope.(from: T, to: T) -> Unit>,
    private val scrollThreshold: Float,
    private val scrollThresholdPadding: AbsolutePixelPadding,
    private val scroller: Scroller,
    private val layoutDirection: LayoutDirection,
    private val shouldItemMove: (draggingItem: Rect, item: Rect) -> Boolean = { draggingItem, item ->
        draggingItem.contains(item.center)
    },
) : ReorderableLazyCollectionStateInterface {
    private val onMoveStateMutex = Mutex()

    internal val orientation: Orientation
        get() = state.layoutInfo.orientation

    private var draggingItemKey by mutableStateOf<Any?>(null)
    private val draggingItemIndex: Int?
        get() = draggingItemLayoutInfo?.index

    override val isAnyItemDragging by derivedStateOf {
        draggingItemKey != null
    }

    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(IntOffset.Zero)
    private var oldDraggingItemIndex by mutableStateOf<Int?>(null)
    private var predictedDraggingItemOffset by mutableStateOf<IntOffset?>(null)

    private val draggingItemLayoutInfo: LazyCollectionItemInfo<T>?
        get() = draggingItemKey?.let { key ->
            state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        }

    internal val draggingItemOffset: Offset
        get() = (draggingItemLayoutInfo?.let {
            val offset = if (it.index != oldDraggingItemIndex || oldDraggingItemIndex == null) {
                oldDraggingItemIndex = null
                predictedDraggingItemOffset = null
                it.offset
            } else {
                predictedDraggingItemOffset ?: it.offset
            }

            draggingItemDraggedDelta +
                (draggingItemInitialOffset.toOffset() - offset.toOffset())
                    .reverseAxisIfNecessary()
        }) ?: Offset.Zero

    private var draggingItemHandleOffset = Offset.Zero

    internal val reorderableKeys = HashSet<Any?>()

    internal var settlingItemKey by mutableStateOf<Any?>(null)
        private set
    internal var settlingItemOffset = Animatable(Offset.Zero, Offset.VectorConverter)
        private set

    private fun Offset.reverseAxisWithReverseLayoutIfNecessary() =
        if (state.layoutInfo.reverseLayout) reverseAxis(orientation) else this

    private fun Offset.reverseAxisWithLayoutDirectionIfNecessary() = when (orientation) {
        Orientation.Vertical -> this
        Orientation.Horizontal -> when (layoutDirection) {
            LayoutDirection.Ltr -> this
            LayoutDirection.Rtl -> reverseAxis(Orientation.Horizontal)
        }
    }

    private fun Offset.reverseAxisIfNecessary() =
        reverseAxisWithReverseLayoutIfNecessary().reverseAxisWithLayoutDirectionIfNecessary()

    internal suspend fun onDragStart(key: Any, handleOffset: Offset) {
        state.layoutInfo.visibleItemsInfo.firstOrNull { item -> item.key == key }?.also {
            val mainAxisOffset = it.offset.getAxis(orientation)
            if (mainAxisOffset < 0) {
                state.animateScrollBy(mainAxisOffset.toFloat(), spring())
            }

            draggingItemKey = key
            draggingItemInitialOffset = it.offset
            draggingItemHandleOffset = handleOffset
        }
    }

    internal fun onDragStop() {
        val previousDraggingItemInitialOffset = draggingItemLayoutInfo?.offset
        val draggingItem = draggingItemLayoutInfo

        if (draggingItemIndex != null && draggingItem != null) {
            settlingItemKey = draggingItemKey
            val startOffset = draggingItemOffset
            val itemMainAxisSizePx = draggingItem.size.getAxis(orientation).coerceAtLeast(1)
            scope.launch {
                settlingItemOffset.snapTo(startOffset)
                val releaseDistancePx = abs(startOffset.getAxis(orientation))
                if (releaseDistancePx <= ReleaseSettleThresholdPx) {
                    settlingItemOffset.snapTo(Offset.Zero)
                } else {
                    val settleProfile = adaptiveReleaseSettleProfile(
                        releaseDistancePx = releaseDistancePx,
                        itemMainAxisSizePx = itemMainAxisSizePx,
                    )
                    settlingItemOffset.animateTo(
                        targetValue = Offset.Zero,
                        animationSpec = spring(
                            dampingRatio = settleProfile.dampingRatio,
                            stiffness = settleProfile.stiffness,
                            visibilityThreshold = Offset.VisibilityThreshold,
                        )
                    )
                }
                settlingItemKey = null
            }
        }

        draggingItemDraggedDelta = Offset.Zero
        draggingItemKey = null
        draggingItemInitialOffset = previousDraggingItemInitialOffset ?: IntOffset.Zero
        scroller.tryStop()
        oldDraggingItemIndex = null
        predictedDraggingItemOffset = null
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset

        val draggingItem = draggingItemLayoutInfo ?: return
        val dragOffset = draggingItemOffset.reverseAxisIfNecessary()
        val startOffset = draggingItem.offset.toOffset() + dragOffset
        val endOffset = startOffset + draggingItem.size.toSize()
        val (contentStartOffset, contentEndOffset) = state.layoutInfo.getScrollAreaOffsets(scrollThresholdPadding)

        val handleOffset = when (state.layoutInfo.reverseLayout ||
            (layoutDirection == LayoutDirection.Rtl && orientation == Orientation.Horizontal)
        ) {
            true -> endOffset - draggingItemHandleOffset
            false -> startOffset + draggingItemHandleOffset
        } + IntOffset.fromAxis(orientation, state.layoutInfo.beforeContentPadding).toOffset()

        val distanceFromStart = (handleOffset.getAxis(orientation) - contentStartOffset).coerceAtLeast(0f)
        val distanceFromEnd = (contentEndOffset - handleOffset.getAxis(orientation)).coerceAtLeast(0f)

        val isScrollingStarted = if (distanceFromStart < scrollThreshold) {
            scroller.start(
                direction = Scroller.Direction.BACKWARD,
                speedMultiplier = getScrollSpeedMultiplier(distanceFromStart),
                maxScrollDistanceProvider = {
                    (draggingItemLayoutInfo?.let {
                        state.layoutInfo.mainAxisViewportSize - it.offset.toOffset().getAxis(orientation) - 1f
                    }) ?: 0f
                },
                onScroll = { moveDraggingItemToEnd(Scroller.Direction.BACKWARD) },
            )
        } else if (distanceFromEnd < scrollThreshold) {
            scroller.start(
                direction = Scroller.Direction.FORWARD,
                speedMultiplier = getScrollSpeedMultiplier(distanceFromEnd),
                maxScrollDistanceProvider = {
                    (draggingItemLayoutInfo?.let {
                        it.offset.toOffset().getAxis(orientation) + it.size.getAxis(orientation) - 1f
                    }) ?: 0f
                },
                onScroll = { moveDraggingItemToEnd(Scroller.Direction.FORWARD) },
            )
        } else {
            scroller.tryStop()
            false
        }

        if (!onMoveStateMutex.tryLock()) return
        if (!scroller.isScrolling && !isScrollingStarted) {
            val draggingItemRect = Rect(startOffset, endOffset)
            val targetItem = findTargetItem(
                draggingItemRect = draggingItemRect,
                items = state.layoutInfo.visibleItemsInfo,
            ) { it.index != draggingItem.index }
            if (targetItem != null) {
                scope.launch { moveItems(draggingItem, targetItem) }
            }
        }
        onMoveStateMutex.unlock()
    }

    private suspend fun moveDraggingItemToEnd(direction: Scroller.Direction) {
        onMoveStateMutex.lock()

        val draggingItem = draggingItemLayoutInfo
        if (draggingItem == null) {
            onMoveStateMutex.unlock()
            return
        }

        val isDraggingItemAtEnd = when (direction) {
            Scroller.Direction.FORWARD -> draggingItem.index == state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            Scroller.Direction.BACKWARD -> draggingItem.index == state.firstVisibleItemIndex
        }
        if (isDraggingItemAtEnd) {
            onMoveStateMutex.unlock()
            return
        }

        val dragOffset = draggingItemOffset.reverseAxisIfNecessary()
        val startOffset = draggingItem.offset.toOffset() + dragOffset
        val endOffset = startOffset + draggingItem.size.toSize()
        val draggingItemRect = Rect(startOffset, endOffset)

        val itemsInContentArea = state.layoutInfo.getItemsInContentArea(scrollThresholdPadding)
            .ifEmpty { state.layoutInfo.visibleItemsInfo }
        val targetItem = findTargetItem(
            draggingItemRect = draggingItemRect,
            items = itemsInContentArea,
            direction = direction.opposite,
        ) ?: itemsInContentArea.let {
            val targetItemFunc = { item: LazyCollectionItemInfo<T> ->
                item.key in reorderableKeys && when (orientation) {
                    Orientation.Vertical -> item.offset.x == draggingItem.offset.x
                    Orientation.Horizontal -> item.offset.y == draggingItem.offset.y
                }
            }
            when (direction) {
                Scroller.Direction.FORWARD -> it.findLast(targetItemFunc)
                Scroller.Direction.BACKWARD -> it.find(targetItemFunc)
            }
        }
        if (targetItem == null) {
            onMoveStateMutex.unlock()
            return
        }

        val isTargetDirectionCorrect = when (direction) {
            Scroller.Direction.FORWARD -> targetItem.index > draggingItem.index
            Scroller.Direction.BACKWARD -> targetItem.index < draggingItem.index
        }
        if (!isTargetDirectionCorrect) {
            onMoveStateMutex.unlock()
            return
        }

        val job = scope.launch { moveItems(draggingItem, targetItem) }
        onMoveStateMutex.unlock()
        job.join()
    }

    private fun findTargetItem(
        draggingItemRect: Rect,
        items: List<LazyCollectionItemInfo<T>> = state.layoutInfo.getItemsInContentArea(scrollThresholdPadding),
        direction: Scroller.Direction = Scroller.Direction.FORWARD,
        additionalPredicate: (LazyCollectionItemInfo<T>) -> Boolean = { true },
    ): LazyCollectionItemInfo<T>? {
        val targetItemFunc = { item: LazyCollectionItemInfo<T> ->
            val targetItemRect = Rect(item.offset.toOffset(), item.size.toSize())
            shouldItemMove(draggingItemRect, targetItemRect) &&
                item.key in reorderableKeys &&
                additionalPredicate(item)
        }
        return when (direction) {
            Scroller.Direction.FORWARD -> items.find(targetItemFunc)
            Scroller.Direction.BACKWARD -> items.findLast(targetItemFunc)
        }
    }

    private val layoutInfoFlow = snapshotFlow { state.layoutInfo }

    private suspend fun moveItems(
        draggingItem: LazyCollectionItemInfo<T>,
        targetItem: LazyCollectionItemInfo<T>,
    ) {
        if (draggingItem.index == targetItem.index) return

        try {
            onMoveStateMutex.withLock {
                if (!isAnyItemDragging) return

                if (draggingItem.index == state.firstVisibleItemIndex || targetItem.index == state.firstVisibleItemIndex) {
                    state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }

                oldDraggingItemIndex = draggingItem.index
                scope.(onMoveState.value)(draggingItem.data, targetItem.data)
                predictedDraggingItemOffset = if (targetItem.index > draggingItem.index) {
                    (targetItem.offset + targetItem.size) - draggingItem.size
                } else {
                    targetItem.offset
                }

                withTimeout(MoveItemsLayoutInfoUpdateMaxWaitDuration) {
                    layoutInfoFlow.take(2).collect()
                }

                oldDraggingItemIndex = null
                predictedDraggingItemOffset = null
            }
        } catch (_: CancellationException) {
        }
    }

    internal fun isItemDragging(key: Any): State<Boolean> {
        return derivedStateOf { key == draggingItemKey }
    }

    private fun getScrollSpeedMultiplier(distance: Float): Float {
        return (1 - ((distance + scrollThreshold) / (scrollThreshold * 2)).coerceIn(0f, 1f)) * 10
    }

    companion object {
        private const val MoveItemsLayoutInfoUpdateMaxWaitDuration = 1000L
    }
}

private data class ReleaseSettleProfile(
    val stiffness: Float,
    val dampingRatio: Float,
)

private fun adaptiveReleaseSettleProfile(
    releaseDistancePx: Float,
    itemMainAxisSizePx: Int,
): ReleaseSettleProfile {
    val normalizedDistance = (releaseDistancePx / itemMainAxisSizePx.toFloat()).coerceIn(0f, 1f)
    val stiffness = Spring.StiffnessMedium +
        (Spring.StiffnessHigh - Spring.StiffnessMedium) * normalizedDistance
    return ReleaseSettleProfile(
        stiffness = stiffness,
        dampingRatio = Spring.DampingRatioNoBouncy,
    )
}

@Stable
interface ReorderableCollectionItemScope {
    fun Modifier.draggableHandle(
        enabled: Boolean = true,
        interactionSource: MutableInteractionSource? = null,
        onDragStarted: (startedPosition: Offset) -> Unit = {},
        onDragStopped: () -> Unit = {},
        dragGestureDetector: DragGestureDetector = DragGestureDetector.Press,
    ): Modifier

    fun Modifier.longPressDraggableHandle(
        enabled: Boolean = true,
        interactionSource: MutableInteractionSource? = null,
        onDragStarted: (startedPosition: Offset) -> Unit = {},
        onDragStopped: () -> Unit = {},
    ): Modifier
}

internal class ReorderableCollectionItemScopeImpl(
    private val reorderableLazyCollectionState: ReorderableLazyCollectionState<*>,
    private val key: Any,
    private val itemPositionProvider: () -> Offset,
) : ReorderableCollectionItemScope {
    override fun Modifier.draggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (startedPosition: Offset) -> Unit,
        onDragStopped: () -> Unit,
        dragGestureDetector: DragGestureDetector,
    ): Modifier = composed {
        var handleOffset by remember { mutableStateOf(Offset.Zero) }
        var handleSize by remember { mutableStateOf(IntSize.Zero) }
        val coroutineScope = rememberCoroutineScope()

        onGloballyPositioned {
            handleOffset = it.positionInRoot()
            handleSize = it.size
        }.draggable(
            key1 = reorderableLazyCollectionState,
            enabled = enabled && (
                reorderableLazyCollectionState.isItemDragging(key).value ||
                    !reorderableLazyCollectionState.isAnyItemDragging
                ),
            interactionSource = interactionSource,
            dragGestureDetector = dragGestureDetector,
            onDragStarted = {
                coroutineScope.launch {
                    val handleOffsetRelativeToItem = handleOffset - itemPositionProvider()
                    val handleCenter = Offset(
                        x = handleOffsetRelativeToItem.x + handleSize.width / 2f,
                        y = handleOffsetRelativeToItem.y + handleSize.height / 2f,
                    )
                    reorderableLazyCollectionState.onDragStart(key, handleCenter)
                }
                onDragStarted(it)
            },
            onDragStopped = {
                reorderableLazyCollectionState.onDragStop()
                onDragStopped()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                reorderableLazyCollectionState.onDrag(dragAmount)
            },
        )
    }

    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (startedPosition: Offset) -> Unit,
        onDragStopped: () -> Unit,
    ) = draggableHandle(
        enabled = enabled,
        interactionSource = interactionSource,
        onDragStarted = onDragStarted,
        onDragStopped = onDragStopped,
        dragGestureDetector = DragGestureDetector.LongPress,
    )
}

@Composable
internal fun ReorderableCollectionItem(
    state: ReorderableLazyCollectionState<*>,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dragging: Boolean,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean) -> Unit,
) {
    var itemPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier.onGloballyPositioned {
            itemPosition = it.positionInRoot()
        }
    ) {
        val itemScope = remember(state, key) {
            ReorderableCollectionItemScopeImpl(
                reorderableLazyCollectionState = state,
                key = key,
                itemPositionProvider = { itemPosition },
            )
        }
        itemScope.content(dragging)
    }

    LaunchedEffect(state.reorderableKeys, enabled) {
        if (enabled) {
            state.reorderableKeys.add(key)
        } else {
            state.reorderableKeys.remove(key)
        }
    }
}
