package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.ext.plus
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.datastore.hasAntigravity
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.reflect.full.primaryConstructor

@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_search_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_page_search_add_provider)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) {
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val fromIndex = from.index
            val toIndex = to.index

            if (fromIndex >= 0 && toIndex >= 0 && fromIndex < settings.searchServices.size && toIndex < settings.searchServices.size) {
                val newServices = settings.searchServices.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                vm.updateSettings(
                    settings.copy(searchServices = newServices)
                )
            }
        }
        val haptic = LocalHapticFeedback.current

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = lazyListState
        ) {
            items(settings.searchServices, key = { it.id }) { service ->
                ReorderableItem(
                    state = reorderableState,
                    key = service.id
                ) { isDragging ->
                    SearchProviderCard(
                        service = service,
                        onEdit = {
                            nav.navigate(Screen.SettingSearchDetail(service.id.toString()))
                        },
                        onDelete = {
                            if (settings.searchServices.size > 1) {
                                val index = settings.searchServices.indexOf(service)
                                val newServices = settings.searchServices.toMutableList()
                                newServices.removeAt(index)
                                vm.updateSettings(
                                    settings.copy(searchServices = newServices)
                                )
                            }
                        },
                        canDelete = settings.searchServices.size > 1,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem()
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                }
                            )
                    )
                }
            }

            item("common_options") {
                CommonOptions(
                    settings = settings,
                    onUpdate = { options ->
                        vm.updateSettings(
                            settings.copy(searchCommonOptions = options)
                        )
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            hasAntigravity = settings.hasAntigravity(),
            onDismiss = { showAddDialog = false },
            onConfirm = { options ->
                showAddDialog = false
                vm.updateSettings(
                    settings.copy(
                        searchServices = listOf(options) + settings.searchServices
                    )
                )
                scope.launch {
                    lazyListState.animateScrollToItem(0)
                }
            }
        )
    }
}

@Composable
private fun AddProviderDialog(
    hasAntigravity: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (SearchServiceOptions) -> Unit
) {
    // "Google Search" rides the Gagy login — hide it unless Gagy is configured.
    val types = remember(hasAntigravity) {
        SearchServiceOptions.TYPES.keys.filter {
            hasAntigravity || it != SearchServiceOptions.GoogleSearchOptions::class
        }
    }
    var selectedType by remember(types) {
        mutableStateOf(types.first())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.setting_page_search_add_provider))
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(types) { type ->
                    val name = SearchServiceOptions.TYPES[type] ?: "Unknown"
                    val isSelected = selectedType == type
                    Card(
                        onClick = { selectedType = type },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AutoAIIcon(
                                name = name,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val instance = selectedType.primaryConstructor!!.callBy(mapOf())
                    onConfirm(instance)
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AutoAIIcon(
                name = service.displayName,
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = service.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                SearchAbilityTagLine(options = service)
            }

            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = null
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(HugeIcons.PencilEdit01, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Delete01, contentDescription = null)
                        },
                        enabled = canDelete
                    )
                }
            }

        }
    }
}

@Composable
fun SearchAbilityTagLine(
    modifier: Modifier = Modifier,
    options: SearchServiceOptions
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Tag(
            type = TagType.DEFAULT,
        ) {
            Text(stringResource(R.string.search_ability_search))
        }
        if (SearchService.getService(options).scrapingParameters(options) != null) {
            Tag(
                type = TagType.DEFAULT,
            ) {
                Text(stringResource(R.string.search_ability_scrape))
            }
        }
    }
}

@Composable
private fun CommonOptions(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onUpdate: (SearchCommonOptions) -> Unit
) {
    var commonOptions by remember(settings.searchCommonOptions) {
        mutableStateOf(settings.searchCommonOptions)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_common_options),
                style = MaterialTheme.typography.titleMedium
            )

            FormItem(
                label = {
                    Text(stringResource(R.string.setting_page_search_result_size))
                }
            ) {
                OutlinedNumberInput(
                    value = commonOptions.resultSize,
                    onValueChange = {
                        commonOptions = commonOptions.copy(resultSize = it)
                        onUpdate(commonOptions)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
