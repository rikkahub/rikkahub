package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

enum class ReorderItemPlacementPolicy {
    LibraryDefault,
    ContentManaged
}

@Composable
fun LazyItemScope.AppReorderableLazyListItem(
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placementPolicy: ReorderItemPlacementPolicy = ReorderItemPlacementPolicy.LibraryDefault,
    contentModifier: Modifier = Modifier,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean, itemModifier: Modifier) -> Unit
) {
    val animateItemModifier = when (placementPolicy) {
        ReorderItemPlacementPolicy.LibraryDefault -> Modifier.animateItem()
        ReorderItemPlacementPolicy.ContentManaged -> Modifier
    }
    val resolvedContentModifier = when (placementPolicy) {
        ReorderItemPlacementPolicy.LibraryDefault -> contentModifier
        ReorderItemPlacementPolicy.ContentManaged -> contentModifier.animateItem()
    }

    ReorderableItem(
        state = state,
        key = key,
        modifier = modifier,
        enabled = enabled,
        animateItemModifier = animateItemModifier
    ) { isDragging ->
        content(isDragging, resolvedContentModifier)
    }
}
