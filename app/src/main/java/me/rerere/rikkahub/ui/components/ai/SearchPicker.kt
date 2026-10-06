package me.rerere.rikkahub.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SearchRemove
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine
import me.rerere.ui.components.ToggleSurface

enum class SearchMode {
    OFF,
    LOCAL,
    BUILT_IN,
}

private val modeShapes = SearchMode.entries.map { it.shape() }

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    model: Model?,
) {
    var showSearchPicker by remember { mutableStateOf(false) }
    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch || model?.tools?.contains(BuiltInTools.Search) == true,
        onClick = {
            showSearchPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (model?.tools?.contains(BuiltInTools.Search) == true) {
                    Icon(
                        imageVector = HugeIcons.AiSearch02,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                } else if (enableSearch && currentService != null) {
                    AutoAIIcon(
                        name = currentService.displayName,
                        color = Color.Transparent
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            var selectingProvider by remember { mutableStateOf(false) }
            // 在服务商选择页时，返回键回到上一页而不是关闭 sheet
            BackHandler(enabled = selectingProvider) {
                selectingProvider = false
            }
            AnimatedContent(
                targetState = selectingProvider,
                transitionSpec = {
                    if (targetState) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "SearchPickerPage"
            ) { selecting ->
                if (selecting) {
                    SearchProviderPicker(
                        settings = settings,
                        onUpdateSearchService = { index ->
                            onUpdateSearchService(index)
                            selectingProvider = false
                        },
                        onBack = { selectingProvider = false }
                    )
                } else {
                    SearchPicker(
                        enableSearch = enableSearch,
                        settings = settings,
                        onUpdateSearchMode = onUpdateSearchMode,
                        model = model,
                        onSelectProvider = { selectingProvider = true },
                        onDismiss = { showSearchPicker = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPicker(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onSelectProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val navBackStack = LocalNavController.current

    val provider = model?.findProvider(settings.providers)
    // Google 和使用 Responses API 的 OpenAI Provider 支持内置搜索
    val supportsBuiltInSearch = provider is ProviderSetting.Google ||
        provider is ProviderSetting.OpenAI && provider.useResponseApi
    // 模型是否已开启内置搜索（可能是不支持的模型残留的孤儿状态）
    val hasBuiltInSearchEnabled = model?.tools?.contains(BuiltInTools.Search) == true
    // 模型支持内置搜索，或已开启内置搜索（后者保证残留状态也能被关闭）时显示模型搜索选项
    val showModelSearch = model != null && (supportsBuiltInSearch || hasBuiltInSearchEnabled)
    val currentMode = when {
        hasBuiltInSearchEnabled -> SearchMode.BUILT_IN
        enableSearch -> SearchMode.LOCAL
        else -> SearchMode.OFF
    }
    val modes = buildList {
        add(SearchMode.OFF)
        add(SearchMode.LOCAL)
        if (showModelSearch) add(SearchMode.BUILT_IN)
    }

    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        val enabled = currentMode != SearchMode.OFF
        PickerValueHeader(
            title = stringResource(R.string.use_web_search),
            value = currentMode,
            hint = when (currentMode) {
                SearchMode.OFF -> stringResource(R.string.search_picker_off_description)
                SearchMode.LOCAL -> stringResource(R.string.search_picker_local_description)
                SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_description)
            },
            modifier = Modifier.padding(bottom = 16.dp),
            label = {
                when (it) {
                    SearchMode.OFF -> stringResource(R.string.search_picker_status_off)
                    SearchMode.LOCAL -> stringResource(R.string.search_picker_local_title)
                    SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_title)
                }
            },
        ) {
            PickerHero(
                shapes = modeShapes,
                index = currentMode.ordinal,
                icon = currentMode.icon(),
                containerColor = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // 连接式按钮组，选中项更宽并带图标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            modes.fastForEachIndexed { index, mode ->
                val selected = mode == currentMode
                val weight by animateFloatAsState(
                    targetValue = if (selected) 1.3f else 1f,
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                )
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { if (!selected) onUpdateSearchMode(mode) },
                    modifier = Modifier
                        .weight(weight)
                        .heightIn(min = 52.dp)
                        .semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
                        exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
                    ) {
                        Row {
                            Icon(
                                imageVector = mode.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                        }
                    }
                    Text(
                        text = when (mode) {
                            SearchMode.OFF -> stringResource(R.string.search_picker_off)
                            SearchMode.LOCAL -> stringResource(R.string.search_picker_local_title)
                            SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_title)
                        },
                        // 三个按钮并排时较长的译文放不下，自动缩小字号
                        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = currentMode == SearchMode.LOCAL,
            enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            SegmentedListItem(
                onClick = onSelectProvider,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier.padding(top = 12.dp),
                leadingContent = {
                    if (currentService != null) {
                        AutoAIIcon(
                            name = currentService.displayName,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Icon(HugeIcons.GlobalSearch, contentDescription = null)
                    }
                },
                supportingContent = currentService?.let {
                    { SearchAbilityTagLine(options = it) }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 搜索设置只和本地搜索的服务商有关，跟着这一行一起出现
                        IconButton(
                            onClick = {
                                onDismiss()
                                navBackStack.navigate(Screen.SettingSearch)
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Settings03,
                                contentDescription = stringResource(R.string.search_picker_title),
                            )
                        }
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    }
                },
            ) {
                Text(
                    text = currentService?.displayName
                        ?: stringResource(R.string.search_picker_select_provider)
                )
            }
        }
    }
}

private fun SearchMode.icon(): ImageVector = when (this) {
    SearchMode.OFF -> HugeIcons.SearchRemove
    SearchMode.LOCAL -> HugeIcons.GlobalSearch
    SearchMode.BUILT_IN -> HugeIcons.AiSearch02
}

private fun SearchMode.shape(): RoundedPolygon = when (this) {
    SearchMode.OFF -> MaterialShapes.Circle
    SearchMode.LOCAL -> MaterialShapes.Cookie6Sided
    SearchMode.BUILT_IN -> MaterialShapes.Cookie9Sided
}

@Composable
private fun SearchProviderPicker(
    settings: Settings,
    onUpdateSearchService: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val services = settings.searchServices
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.search_picker_select_provider),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = null)
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(services) { index, service ->
                val selected = settings.searchServiceSelected == index
                SegmentedListItem(
                    selected = selected,
                    onClick = { onUpdateSearchService(index) },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = services.size),
                    leadingContent = {
                        AutoAIIcon(
                            name = service.displayName,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    supportingContent = {
                        SearchAbilityTagLine(options = service)
                    },
                    trailingContent = {
                        RadioButton(selected = selected, onClick = null)
                    },
                ) {
                    Text(service.displayName)
                }
            }
        }
    }
}
