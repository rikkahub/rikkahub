package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.icons.HeartIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.overlayEdgeScrollGuard
import me.rerere.rikkahub.ui.testing.ChatUiTestTags
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberScroller
import kotlin.math.abs
import kotlin.uuid.Uuid

private const val FavoriteReleaseThresholdPx = 0.5f

@Composable
fun ModelSelector(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    allowClear: Boolean = false,
    onSelect: (Model) -> Unit
) {
    var popup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val model = providers.findModelById(modelId ?: Uuid.random())

    if (!onlyIcon) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    popup = true
                },
                modifier = modifier.testTag(ChatUiTestTags.MODEL_SELECTOR_TRIGGER)
            ) {
                model?.modelId?.let {
                    AutoAIIcon(
                        it, Modifier
                            .padding(end = 4.dp)
                            .size(36.dp),
                        color = Color.Transparent
                    )
                }
                Text(
                    text = model?.displayName ?: stringResource(R.string.model_list_select_model),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (allowClear && model != null) {
                IconButton(
                    onClick = {
                        onSelect(Model())
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Clear"
                    )
                }
            }
        }
    } else {
        IconButton(
            onClick = {
                popup = true
            },
            modifier = modifier.testTag(ChatUiTestTags.MODEL_SELECTOR_TRIGGER),
        ) {
            if (model != null) {
                AutoAIIcon(
                    modifier = Modifier.size(36.dp),
                    name = model.modelId,
                    color = Color.Transparent
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Brain02,
                    contentDescription = stringResource(R.string.setting_model_page_chat_model),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (popup) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                popup = false
            },
            sheetState = state,
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxHeight(0.8f)
                    .imePadding()
                    .semantics { testTagsAsResourceId = true },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val filteredProviderSettings = providers.fastFilter {
                    it.enabled && it.models.fastAny { model -> model.type == type }
                }
                ModelList(
                    currentModel = modelId,
                    providers = filteredProviderSettings,
                    modelType = type,
                    onSelect = {
                        onSelect(it)
                        scope.launch {
                            state.hide()
                            popup = false
                        }
                    },
                    onDismiss = {
                        scope.launch {
                            state.hide()
                            popup = false
                        }
                    }
                )
            }
        }
    }
}

private fun reorderFavoriteModels(
    allFavoriteModelIds: List<Uuid>,
    reorderedVisibleFavoriteIds: List<Uuid>,
    visibleFavoriteIds: Set<Uuid>
): List<Uuid> {
    var nextVisibleIndex = 0
    return allFavoriteModelIds.map { modelId ->
        if (modelId in visibleFavoriteIds) {
            reorderedVisibleFavoriteIds[nextVisibleIndex++]
        } else {
            modelId
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.isItemVisible(index: Int): Boolean {
    return layoutInfo.visibleItemsInfo.any { it.index == index }
}

@Composable
private fun ColumnScope.ModelList(
    currentModel: Uuid? = null,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()
    val settings = settingsStore.settingsFlow
        .collectAsStateWithLifecycle()
    val currentSettings = settings.value

    val favoriteModelIdsFromSettings = remember(currentSettings.favoriteModels, currentSettings.providers, modelType) {
        currentSettings.favoriteModels.filter { modelId ->
            currentSettings.providers.findModelById(modelId)?.type == modelType
        }
    }

    // 顺序和成员分开维护
    // 拖拽时只改本地顺序 供应商区只看成员集合
    var localFavoriteModelIds by remember(modelType) {
        mutableStateOf(favoriteModelIdsFromSettings)
    }
    var localFavoriteModelIdSet by remember(modelType) {
        mutableStateOf(favoriteModelIdsFromSettings.toSet())
    }
    var isDraggingFavorites by remember(modelType) { mutableStateOf(false) }

    // 非拖拽阶段才让持久化顺序回流覆盖本地临时顺序
    LaunchedEffect(favoriteModelIdsFromSettings, isDraggingFavorites) {
        if (!isDraggingFavorites) {
            if (localFavoriteModelIds != favoriteModelIdsFromSettings) {
                localFavoriteModelIds = favoriteModelIdsFromSettings
            }
            localFavoriteModelIdSet = favoriteModelIdsFromSettings.toSet()
        }
    }

    val favoriteModels = remember(localFavoriteModelIds, currentSettings.providers, modelType) {
        localFavoriteModelIds.mapNotNull { modelId ->
            val model = currentSettings.providers.findModelById(modelId) ?: return@mapNotNull null
            if (model.type != modelType) return@mapNotNull null
            val provider = model.findProvider(
                providers = currentSettings.providers,
                checkOverwrite = false
            ) ?: return@mapNotNull null
            model to provider
        }
    }

    // 所有收藏写入都收口到这里
    fun persistFavoriteModels(updatedFavoriteModelIds: List<Uuid>) {
        coroutineScope.launch {
            settingsStore.update { settings ->
                settings.copy(favoriteModels = updatedFavoriteModelIds)
            }
        }
    }

    fun updateFavoritesImmediately(updatedFavoriteModelIds: List<Uuid>) {
        val visibleFavoriteIds = updatedFavoriteModelIds.filter { modelId ->
            currentSettings.providers.findModelById(modelId)?.type == modelType
        }
        localFavoriteModelIds = visibleFavoriteIds
        localFavoriteModelIdSet = visibleFavoriteIds.toSet()
        persistFavoriteModels(updatedFavoriteModelIds)
    }

    // 松手后再把拖拽后的顺序一次性落盘
    fun commitDraggedFavoriteOrder() {
        if (localFavoriteModelIds == favoriteModelIdsFromSettings) return
        val reorderedFavoriteModelIds = reorderFavoriteModels(
            allFavoriteModelIds = currentSettings.favoriteModels,
            reorderedVisibleFavoriteIds = localFavoriteModelIds,
            visibleFavoriteIds = favoriteModelIdsFromSettings.toSet()
        )
        persistFavoriteModels(reorderedFavoriteModelIds)
    }

    var searchKeywords by remember { mutableStateOf("") }

    val typeFilteredModelsByProvider = remember(providers, modelType) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter { it.type == modelType }
        }
    }

    val searchFilteredModelsByProvider = remember(providers, modelType, searchKeywords) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.type == modelType && it.displayName.contains(searchKeywords, true)
            }
        }
    }

    // 计算当前选中模型的位置
    val selectedModelPosition = remember(currentModel, favoriteModels, providers, typeFilteredModelsByProvider) {
        if (currentModel == null) return@remember 0

        var position = 0

        // 跳过无providers提示
        if (providers.isEmpty()) {
            position += 1
        }

        // 检查是否在收藏列表中
        val favoriteIndex = favoriteModels.indexOfFirst { it.first.id == currentModel }
        if (favoriteIndex >= 0) {
            if (favoriteModels.isNotEmpty()) {
                position += 1 // favorite header
            }
            position += favoriteIndex
            return@remember position
        }

        // 跳过收藏列表
        if (favoriteModels.isNotEmpty()) {
            position += 1 // favorite header
            position += favoriteModels.size
        }

        // 在providers中查找
        for (provider in providers) {
            position += 1 // provider header
            val models = typeFilteredModelsByProvider[provider.id].orEmpty()
            val modelIndex = models.indexOfFirst { it.id == currentModel }
            if (modelIndex >= 0) {
                position += modelIndex
                return@remember position
            }
            position += models.size
        }

        0
    }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedModelPosition
    )
    // 限制边缘自动滚动速度
    // 快速拖拽时别让列表冲得过猛
    val reorderScroller = rememberScroller(lazyListState, 2200f)
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scroller = reorderScroller
    ) { from, to ->
        // 计算favorite models在列表中的位置偏移
        var favoriteStartIndex = 0
        if (providers.isEmpty()) {
            favoriteStartIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            favoriteStartIndex += 1 // favorite header
        }

        val fromIndex = from.index - favoriteStartIndex
        val toIndex = to.index - favoriteStartIndex

        // 只处理favorite models范围内的拖拽
        if (fromIndex >= 0 && toIndex >= 0 &&
            fromIndex < favoriteModels.size && toIndex < favoriteModels.size
        ) {
            localFavoriteModelIds = localFavoriteModelIds.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    val favoriteModelCount = favoriteModels.size
    // 预先算好 provider header 的索引
    // badge 联动时不用每次都反复扫整列
    val providerPositions = remember(providers, favoriteModelCount, searchFilteredModelsByProvider) {
        var currentIndex = 0
        if (providers.isEmpty()) {
            currentIndex = 1 // no providers item
        }
        if (favoriteModelCount > 0) {
            currentIndex += 1 // favorite header
            currentIndex += favoriteModelCount // favorite models
        }

        providers.map { provider ->
            val position = currentIndex
            currentIndex += 1 // provider header
            currentIndex += searchFilteredModelsByProvider[provider.id].orEmpty().size
            provider.id to position
        }.toMap()
    }

    Surface(
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        OutlinedTextField(
            value = searchKeywords,
            onValueChange = { searchKeywords = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.model_list_search_placeholder),
                )
            },
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            leadingIcon = {
                Icon(HugeIcons.Search01, null)
            },
            maxLines = 1,
        )
    }

    LazyColumn(
        state = lazyListState,
        // 拖拽时关掉普通滚动
        // 避免和 reorder auto scroll 互相抢手势
        userScrollEnabled = !isDraggingFavorites,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag(ChatUiTestTags.MODEL_SELECTOR_LIST)
            .overlayEdgeScrollGuard(lazyListState),
    ) {
        if (providers.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.model_list_no_providers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.gray6,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (favoriteModels.isNotEmpty()) {
            stickyHeader {
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag(ChatUiTestTags.MODEL_SELECTOR_FAVORITE_HEADER)
                        .padding(bottom = 4.dp, top = 8.dp)
                )
            }

            items(
                items = favoriteModels,
                key = { "favorite:" + it.first.id.toString() },
                contentType = { "favorite_model" }
            ) { (model, provider) ->
                FavoriteModelReorderableItem(
                    state = reorderableState,
                    key = "favorite:" + model.id.toString(),
                ) { isDragging, itemModifier ->
                    ModelItem(
                        model = model,
                        onSelect = onSelect,
                        modifier = itemModifier
                            .testTag(ChatUiTestTags.MODEL_SELECTOR_FAVORITE_ITEM)
                            .scale(if (isDragging) 0.95f else 1f),
                        providerSetting = provider,
                        select = model.id == currentModel,
                        onDismiss = {
                            onDismiss()
                        },
                        tail = {
                            IconButton(
                                onClick = {
                                    updateFavoritesImmediately(
                                        currentSettings.favoriteModels.filter { it != model.id }
                                    )
                                }
                            ) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        dragHandle = {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        isDraggingFavorites = true
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        isDraggingFavorites = false
                                        commitDraggedFavoriteOrder()
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        providers.fastForEach { providerSetting ->
            stickyHeader(key = "header:${providerSetting.id}") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = providerSetting.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    ProviderBalanceText(
                        providerSetting = providerSetting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            items(
                items = searchFilteredModelsByProvider[providerSetting.id].orEmpty(),
                key = { it.id },
                contentType = { "provider_model" }
            ) { model ->
                val favorite = model.id in localFavoriteModelIdSet
                ModelItem(
                    model = model,
                    onSelect = onSelect,
                    modifier = Modifier.animateItem(),
                    providerSetting = providerSetting,
                    select = currentModel == model.id,
                    onDismiss = {
                        onDismiss()
                    },
                    tail = {
                        IconButton(
                            onClick = {
                                updateFavoritesImmediately(
                                    if (favorite) {
                                        currentSettings.favoriteModels.filter { it != model.id }
                                    } else {
                                        currentSettings.favoriteModels + model.id
                                    }
                                )
                            }
                        ) {
                            if (favorite) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Favourite,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    // 供应商Badge行
    val providerBadgeListState = rememberLazyListState()
    // 拖拽收藏时暂停 badge 联动
    // 避免横向和纵向滚动同时抢动画
    LaunchedEffect(lazyListState, providerPositions, providers, isDraggingFavorites) {
        if (isDraggingFavorites) return@LaunchedEffect
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .map { firstVisibleItemIndex ->
                if (firstVisibleItemIndex <= 0) return@map null

                val currentProvider = providerPositions.entries.findLast { (_, position) ->
                    firstVisibleItemIndex > position
                }
                providers.indexOfFirst { it.id == currentProvider?.key }.takeIf { it >= 0 } ?: 0
            }
            .distinctUntilChanged()
            .debounce(100)
            .collect { providerIndex ->
                if (providerIndex == null) {
                    if (providerBadgeListState.firstVisibleItemIndex != 0) {
                        providerBadgeListState.requestScrollToItem(0)
                    }
                } else if (!providerBadgeListState.isItemVisible(providerIndex)) {
                    providerBadgeListState.animateScrollToItem(providerIndex)
                }
            }
    }
    if (providers.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            state = providerBadgeListState
        ) {
            items(providers) { provider ->
                AssistChip(
                    onClick = {
                        val position = providerPositions[provider.id] ?: 0
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(position)
                        }
                    },
                    label = {
                        Text(provider.name)
                    },
                    leadingIcon = {
                        AutoAIIcon(name = provider.name, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

private data class FavoriteReleaseSettleProfile(
    val stiffness: Float,
    val dampingRatio: Float,
)

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@Composable
private fun LazyItemScope.FavoriteModelReorderableItem(
    state: sh.calvin.reorderable.ReorderableLazyListState,
    key: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean, itemModifier: Modifier) -> Unit,
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
        if (releaseDistancePx <= FavoriteReleaseThresholdPx) {
            isReleaseSettling = false
            releaseOffset.snapTo(Offset.Zero)
            return@LaunchedEffect
        }

        val settleProfile = adaptiveReleaseSettleProfile(
            releaseDistancePx = releaseDistancePx,
            itemMainAxisSizePx = when (orientation) {
                Orientation.Vertical -> itemSize.height
                Orientation.Horizontal -> itemSize.width
            }.coerceAtLeast(1)
        )

        isReleaseSettling = true
        releaseOffset.snapTo(startOffset)
        releaseOffset.animateTo(
            targetValue = Offset.Zero,
            animationSpec = spring(
                dampingRatio = settleProfile.dampingRatio,
                stiffness = settleProfile.stiffness,
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

        else -> Modifier.animateItem()
    }

    sh.calvin.reorderable.ReorderableCollectionItem(
        state,
        key,
        modifier
            .onSizeChanged { itemSize = it }
            .then(stateModifier),
        enabled,
        isDragging,
    ) { dragging ->
        content(dragging, Modifier)
    }
}

private fun Modifier.mainAxisTranslation(
    orientation: Orientation,
    offsetProvider: () -> Offset,
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
    itemMainAxisSizePx: Int,
): FavoriteReleaseSettleProfile {
    val normalizedDistance = (releaseDistancePx / itemMainAxisSizePx.toFloat()).coerceIn(0f, 1f)
    val stiffness = Spring.StiffnessMedium +
        (Spring.StiffnessHigh - Spring.StiffnessMedium) * normalizedDistance
    return FavoriteReleaseSettleProfile(
        stiffness = stiffness,
        dampingRatio = Spring.DampingRatioNoBouncy,
    )
}

private fun mainAxisDistance(
    offset: Offset,
    orientation: Orientation,
): Float {
    return when (orientation) {
        Orientation.Vertical -> abs(offset.y)
        Orientation.Horizontal -> abs(offset.x)
    }
}

@Composable
private fun ModelItem(
    model: Model,
    providerSetting: ProviderSetting,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (select) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (select) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        enabled = true,
                        onLongClick = {
                            onDismiss()
                            navController.navigate(
                                Screen.SettingProviderDetail(
                                    providerSetting.id.toString()
                                )
                            )
                        },
                        onClick = { onSelect(model) },
                        interactionSource = interactionSource,
                        indication = LocalIndication.current
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ModelTypeTag(model = model)

                        ModelModalityTag(model = model)

                        ModelAbilityTag(model = model)
                    }
                }
                tail()
            }
            dragHandle?.let { it() }
        }
    }
}

@Composable
fun ModelTypeTag(model: Model) {
    Tag(
        type = TagType.INFO
    ) {
        Text(
            text = stringResource(
                when (model.type) {
                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                }
            )
        )
    }
}

@Composable
fun ModelModalityTag(model: Model) {
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
        )
        model.outputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun ModelAbilityTag(model: Model) {
    model.abilities.fastForEach { ability ->
        when (ability) {
            ModelAbility.TOOL -> {
                Tag(
                    type = TagType.WARNING
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
                    )
                }
            }

            ModelAbility.REASONING -> {
                Tag(
                    type = TagType.INFO
                ) {
                    Icon(
                        painter = painterResource(R.drawable.deepthink),
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}
