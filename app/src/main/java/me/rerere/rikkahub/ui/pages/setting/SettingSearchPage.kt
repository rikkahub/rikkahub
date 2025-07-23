package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.utils.plus
import me.rerere.search.*
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.reflect.full.primaryConstructor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    
    // 简化启用状态管理
    val serviceStates = remember { mutableStateMapOf<String, Boolean>() }
    
    // 生成唯一ID
    fun getServiceId(index: Int, service: SearchServiceOptions) = 
        "${index}_${service::class.simpleName}_${service.hashCode()}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (isSelectionMode) "已选择 ${selectedIndices.size} 项" 
                        else stringResource(R.string.setting_page_search_title)
                    )
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { 
                            isSelectionMode = false
                            selectedIndices.clear() 
                        }) {
                            Icon(Lucide.X, contentDescription = "取消选择")
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    when {
                        isSelectionMode -> {
                            IconButton(onClick = {
                                if (selectedIndices.size == settings.searchServices.size) {
                                    selectedIndices.clear()
                                } else {
                                    selectedIndices.clear()
                                    selectedIndices.addAll(settings.searchServices.indices)
                                }
                            }) {
                                Icon(Lucide.Check, contentDescription = "全选")
                            }
                        }
                        settings.searchServices.isNotEmpty() -> {
                            TextButton(onClick = { isSelectionMode = true }) {
                                Text("选择")
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = isSelectionMode && selectedIndices.isNotEmpty()) {
                BatchOperationBar(
                    onDelete = {
                        if (settings.searchServices.size - selectedIndices.size >= 1) {
                            vm.updateSettings(settings.copy(
                                searchServices = settings.searchServices.filterIndexed { index, _ -> 
                                    index !in selectedIndices 
                                }
                            ))
                            selectedIndices.clear()
                            isSelectionMode = false
                        }
                    },
                    onToggleEnable = { enable ->
                        selectedIndices.forEach { index ->
                            serviceStates[getServiceId(index, settings.searchServices[index])] = enable
                        }
                        selectedIndices.clear()
                        isSelectionMode = false
                    },
                    canDelete = settings.searchServices.size - selectedIndices.size >= 1
                )
            }
        }
    ) { paddingValues ->
        val lazyListState = rememberLazyListState()
        val haptic = LocalHapticFeedback.current
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            if (!isSelectionMode) {
                val fromIndex = from.index - 1
                val toIndex = to.index - 1
                if (fromIndex in settings.searchServices.indices && toIndex in settings.searchServices.indices) {
                    vm.updateSettings(settings.copy(
                        searchServices = settings.searchServices.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                    ))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = paddingValues + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = lazyListState
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_search_providers),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    if (!isSelectionMode) {
                        OutlinedButton(onClick = {
                            vm.updateSettings(settings.copy(
                                searchServices = settings.searchServices + SearchServiceOptions.BingLocalOptions()
                            ))
                        }) {
                            Icon(Lucide.Plus, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.setting_page_search_add_provider))
                        }
                    }
                }
            }

            itemsIndexed(
                settings.searchServices, 
                key = { index, service -> getServiceId(index, service) }
            ) { index, service ->
                val serviceId = getServiceId(index, service)
                val isEnabled = serviceStates.getOrPut(serviceId) { true }
                
                ReorderableItem(
                    state = reorderableState,
                    key = serviceId,
                    enabled = !isSelectionMode
                ) { isDragging ->
                    SearchProviderCard(
                        service = service,
                        isEnabled = isEnabled,
                        onToggleEnabled = { serviceStates[serviceId] = it },
                        onUpdateService = { updatedService ->
                            vm.updateSettings(settings.copy(
                                searchServices = settings.searchServices.toMutableList().apply {
                                    set(index, updatedService)
                                }
                            ))
                        },
                        onDeleteService = {
                            if (settings.searchServices.size > 1) {
                                vm.updateSettings(settings.copy(
                                    searchServices = settings.searchServices.filterIndexed { i, _ -> i != index }
                                ))
                            }
                        },
                        canDelete = settings.searchServices.size > 1,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        isSelectionMode = isSelectionMode,
                        isSelected = index in selectedIndices,
                        onSelectionChange = { selected ->
                            if (selected) selectedIndices.add(index) else selectedIndices.remove(index)
                        },
                        dragHandle = {
                            if (!isSelectionMode) {
                                Icon(
                                    Lucide.GripHorizontal, null,
                                    Modifier.longPressDraggableHandle(
                                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    )
                                )
                            }
                        }
                    )
                }
            }

            item(key = "common") {
                CommonOptions(settings) { options ->
                    vm.updateSettings(settings.copy(searchCommonOptions = options))
                }
            }
        }
    }
}

@Composable
private fun BatchOperationBar(
    onDelete: () -> Unit,
    onToggleEnable: (Boolean) -> Unit,
    canDelete: Boolean
) {
    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(onClick = { onToggleEnable(true) }) { Text("启用") }
            OutlinedButton(onClick = { onToggleEnable(false) }) { Text("禁用") }
            OutlinedButton(onClick = onDelete, enabled = canDelete) {
                Icon(Lucide.Trash2, null, Modifier.size(18.dp))
                Text("删除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onUpdateService: (SearchServiceOptions) -> Unit,
    onDeleteService: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectionChange: (Boolean) -> Unit = {},
    dragHandle: @Composable () -> Unit = {}
) {
    var options by remember(service) { mutableStateOf(service) }

    Card(
        modifier = modifier,
        onClick = if (isSelectionMode) {{ onSelectionChange(!isSelected) }} else null
    ) {
        Column(
            modifier = Modifier.animateContentSize().fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSelectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = onSelectionChange)
                }
                
                Select(
                    options = SearchServiceOptions.TYPES.keys.toList(),
                    selectedOption = options::class,
                    optionToString = { SearchServiceOptions.TYPES[it] ?: "[Unknown]" },
                    onOptionSelected = {
                        options = it.primaryConstructor!!.callBy(mapOf())
                        onUpdateService(options)
                    },
                    optionLeading = {
                        AutoAIIcon(
                            SearchServiceOptions.TYPES[it] ?: it.simpleName ?: "unknown",
                            Modifier.size(24.dp)
                        )
                    },
                    leading = {
                        AutoAIIcon(
                            SearchServiceOptions.TYPES[options::class] ?: "unknown",
                            Modifier.size(24.dp)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSelectionMode
                )
                
                if (!isSelectionMode) {
                    Switch(checked = isEnabled, onCheckedChange = onToggleEnabled)
                }
            }

            if (!isSelectionMode) {
                ProviderOptions(options) {
                    options = it
                    onUpdateService(options)
                }

                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    SearchService.getService(options).Description()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canDelete) {
                        IconButton(onClick = onDeleteService) {
                            Icon(Lucide.Trash2, stringResource(R.string.setting_page_search_delete_provider))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    dragHandle()
                }
            }
        }
    }
}

@Composable
private fun ProviderOptions(
    options: SearchServiceOptions,
    onUpdate: (SearchServiceOptions) -> Unit
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> ApiKeyInput("API Key", options.apiKey) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.ExaOptions -> ApiKeyInput("API Key", options.apiKey) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.ZhipuOptions -> ApiKeyInput("API Key", options.apiKey) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.LinkUpOptions -> ApiKeyInput("API Key", options.apiKey) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.SearXNGOptions -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextInput("API URL", options.url) { onUpdate(options.copy(url = it)) }
            TextInput("Engines", options.engines) { onUpdate(options.copy(engines = it)) }
            TextInput("Language", options.language) { onUpdate(options.copy(language = it)) }
        }
        is SearchServiceOptions.BingLocalOptions -> {}
    }
}

@Composable
private fun ApiKeyInput(label: String, value: String, onValueChange: (String) -> Unit) =
    TextInput(label, value, onValueChange)

@Composable
private fun TextInput(label: String, value: String, onValueChange: (String) -> Unit) {
    FormItem(label = { Text(label) }) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CommonOptions(settings: Settings, onUpdate: (SearchCommonOptions) -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_common_options),
                style = MaterialTheme.typography.titleMedium
            )
            FormItem(label = { Text(stringResource(R.string.setting_page_search_result_size)) }) {
                OutlinedNumberInput(
                    value = settings.searchCommonOptions.resultSize,
                    onValueChange = { onUpdate(settings.searchCommonOptions.copy(resultSize = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
