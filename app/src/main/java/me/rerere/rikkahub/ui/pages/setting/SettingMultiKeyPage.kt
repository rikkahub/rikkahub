package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import me.rerere.hugeicons.stroke.Connect
import androidx.compose.foundation.lazy.items
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.UiState

import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer


import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowRight01

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.withTimeout
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState

import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.ApiKeyConfig
import me.rerere.ai.util.ApiKeyStatus
import me.rerere.ai.util.LoadBalanceStrategy
import me.rerere.ai.util.parseLegacyApiKeys
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingMultiKeyPage(id: Uuid) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val provider = settings.providers.find { it.id == id } ?: return
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    var internalProvider by remember(provider) { mutableStateOf(provider) }
    val apiKeys = internalProvider.apiKeys

    val totalKeys = apiKeys.size
    val normalKeys = apiKeys.count { it.status == ApiKeyStatus.ACTIVE }
    val errorKeys = apiKeys.count { it.status == ApiKeyStatus.ERROR }

    fun saveProvider(p: ProviderSetting) {
        val newSettings = settings.copy(
            providers = settings.providers.map { if (it.id == p.id) p else it }
        )
        scope.launch { settingsStore.update(newSettings) }
        internalProvider = p
    }

    fun updateKeys(newKeys: List<ApiKeyConfig>) {
        val updated = internalProvider.copyProvider(apiKeys = newKeys)
        updated.syncApiKeyString()
        saveProvider(updated)
    }

    val providerManager = koinInject<ProviderManager>()
    val strUndo = ctx.getString(R.string.multi_key_undo)

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val mutableKeys = apiKeys.toMutableList()
        val item = mutableKeys.removeAt(from.index)
        mutableKeys.add(to.index, item)
        updateKeys(mutableKeys)
    }

    SettingMultiKeyContent(
        internalProvider = internalProvider,
        saveProvider = ::saveProvider,
        updateKeys = ::updateKeys,
        apiKeys = apiKeys,
        errorKeys = errorKeys,
        normalKeys = normalKeys,
        totalKeys = totalKeys,
        providerManager = providerManager,
        snackbarHostState = snackbarHostState,
        lazyListState = lazyListState,
        reorderableState = reorderableState,
        strUndo = strUndo,
        showTopBar = true,
        scope = scope,
    )
}

/**
 * 无 Scaffold 的内容体，可嵌入其他页面（如 ProviderDetail 的 Key tab）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingMultiKeyContent(
    internalProvider: ProviderSetting,
    saveProvider: (ProviderSetting) -> Unit,
    updateKeys: (List<ApiKeyConfig>) -> Unit,
    apiKeys: List<ApiKeyConfig>,
    errorKeys: Int,
    normalKeys: Int,
    totalKeys: Int,
    providerManager: ProviderManager,
    snackbarHostState: SnackbarHostState,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState,
    strUndo: String,
    showTopBar: Boolean = false,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val ctx = LocalContext.current

    var expanded by rememberSaveable { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp)
                .imePadding()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                ),
            state = lazyListState,
            contentPadding = PaddingValues(vertical = 16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                IosCard {
                    StatRow(ctx.getString(R.string.multi_key_total), "$totalKeys")
                    StatRow(ctx.getString(R.string.multi_key_status_active), "$normalKeys", color = Color(0xFF4CAF50))
                    StatRow(ctx.getString(R.string.multi_key_status_error), "$errorKeys",
                        color = if (errorKeys > 0) MaterialTheme.colorScheme.error else Color.Unspecified)

                    var showStrategy by remember { mutableStateOf(false) }
                    StrategyRow(
                        strategy = internalProvider.keyManagement.strategy,
                        onClick = { showStrategy = true }
                    )
                    if (showStrategy) {
                        StrategySheet(
                            current = internalProvider.keyManagement.strategy,
                            onSelect = { strategy ->
                                val km = internalProvider.keyManagement.copy(strategy = strategy)
                                val updated = internalProvider.copyProvider(keyManagement = km)
                                saveProvider(updated)
                                showStrategy = false
                            },
                            onDismiss = { showStrategy = false }
                        )
                }
            }

            item {
                if (apiKeys.isEmpty()) {
                    val legacyKeys = parseLegacyApiKeys(internalProvider.getLegacyApiKey())
                    if (legacyKeys.isNotEmpty()) {
                        LaunchedEffect(Unit) {
                            updateKeys(legacyKeys)
                        }
                    } else {
                        IosCard {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(ctx.getString(R.string.multi_key_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var showAdd by remember { mutableStateOf(false) }
                                Button(onClick = { showAdd = true }) {
                                    Icon(HugeIcons.PlusSign, null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(ctx.getString(R.string.multi_key_add))
                                }
                                if (showAdd) {
                                    AddKeysSheet(
                                        onDismiss = { showAdd = false },
                                        onAdd = { newKeys ->
                                            val existingSet = apiKeys.map { it.key.trim() }.toSet()
                                            val unique = newKeys.filter { it.trim() !in existingSet && it.isNotBlank() }.distinct()
                                            if (unique.isNotEmpty()) {
                                                val added = unique.map { ApiKeyConfig(key = it.trim(), name = "Key ${apiKeys.size + 1}") }
                                                updateKeys(apiKeys + added)
                                                Toast.makeText(ctx, ctx.getString(R.string.multi_key_imported, added.size), Toast.LENGTH_SHORT).show()
                                            }
                                            showAdd = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (apiKeys.isNotEmpty()) {
                items(apiKeys.size, key = { apiKeys[it].id }) { index ->
                    val key = apiKeys[index]
                    ReorderableItem(
                        state = reorderableState,
                        key = key.id
                    ) { isDragging ->
                        val swipeState = rememberSwipeToDismissBoxState()

                        SwipeToDismissBox(
                            state = swipeState,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    } else {
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                },
                            backgroundContent = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { scope.launch { swipeState.reset() } }
                                    ) {
                                        Icon(HugeIcons.Cancel01, null)
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            scope.launch {
                                                val removed = key
                                                val ri = apiKeys.indexOf(key)
                                                val newKeys = apiKeys.toMutableList().apply { removeAt(ri) }
                                                updateKeys(newKeys)
                                                val result = snackbarHostState.showSnackbar(
                                                    message = ctx.getString(R.string.multi_key_deleted,
                                                        removed.name.ifBlank { removed.key.take(8) + "..." }),
                                                    actionLabel = strUndo,
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    val cur = internalProvider.apiKeys.toMutableList()
                                                    cur.add(if (ri <= cur.size) ri else cur.size, removed)
                                                    updateKeys(cur)
                                                }
                                                swipeState.reset()
                                            }
                                        }
                                    ) {
                                        Icon(HugeIcons.Delete01, "Delete")
                                    }
                                }
                            },
                            enableDismissFromStartToEnd = false,
                            gesturesEnabled = true,
                        ) {
                            IosCard {
                                KeyRow(
                                    config = key,
                                    onToggle = { enabled ->
                                        val newStatus = if (enabled) ApiKeyStatus.ACTIVE else ApiKeyStatus.DISABLED
                                        val idx = apiKeys.indexOf(key)
                                        val newKeys = apiKeys.toMutableList().apply { set(idx, key.copy(status = newStatus)) }
                                        updateKeys(newKeys)
                                    },
                                    onEdit = { updated ->
                                        val idx = apiKeys.indexOf(key)
                                        val newKeys = apiKeys.toMutableList().apply { set(idx, updated) }
                                        updateKeys(newKeys)
                                    },
                                    onTest = { configToTest, onResult ->
                                        scope.launch {
                                            try {
                                                val testProvider = internalProvider.copyProvider(
                                                    apiKeys = listOf(configToTest),
                                                    keyManagement = internalProvider.keyManagement
                                                )
                                                testProvider.syncApiKeyString()
                                                withTimeout(15_000L) {
                                                    val model = internalProvider.models
                                                        .firstOrNull { it.type == ModelType.CHAT }
                                                    if (model != null) {
                                                        providerManager.getProviderByType(testProvider)
                                                            .generateText(
                                                                providerSetting = testProvider,
                                                                messages = listOf(
                                                                    UIMessage.system("You are a helpful assistant. Reply only with the word 'ok', nothing else."),
                                                                    UIMessage.user("Say ok")
                                                                ),
                                                                params = TextGenerationParams(model = model)
                                                            )
                                                    } else {
                                                        providerManager.getProviderByType(testProvider)
                                                            .listModels(testProvider)
                                                    }
                                                }
                                                val idx = apiKeys.indexOf(configToTest)
                                                val newKeys = apiKeys.toMutableList().apply {
                                                    set(idx, configToTest.copy(
                                                        status = ApiKeyStatus.ACTIVE,
                                                        lastError = null,
                                                        lastErrorAt = null
                                                    ))
                                                }
                                                updateKeys(newKeys)
                                                onResult(null)
                                            } catch (e: Exception) {
                                                val idx = apiKeys.indexOf(configToTest)
                                                val newKeys = apiKeys.toMutableList().apply {
                                                    set(idx, configToTest.copy(
                                                        status = ApiKeyStatus.ERROR,
                                                        lastError = e.message?.take(200),
                                                        lastErrorAt = System.currentTimeMillis()
                                                    ))
                                                }
                                                updateKeys(newKeys)
                                                onResult(e)
                                            }
                                        }
                                    },
                                    isDragHandle = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        var showAdd by remember { mutableStateOf(false) }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset),
        ) {
            Button(
                onClick = { showAdd = true },
            ) {
                Icon(HugeIcons.PlusSign, null)
                Spacer(Modifier.size(8.dp))
                Text(ctx.getString(R.string.multi_key_add))
            }
            if (errorKeys > 0) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        val removedErrorKeys = apiKeys.filter { it.status == ApiKeyStatus.ERROR }
                        val good = apiKeys.filter { it.status != ApiKeyStatus.ERROR }
                        val deleted = errorKeys
                        updateKeys(good)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = ctx.getString(R.string.multi_key_deleted_errors, deleted),
                                actionLabel = ctx.getString(R.string.multi_key_undo),
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                updateKeys(internalProvider.apiKeys + removedErrorKeys)
                            }
                        }
                    },
                ) {
                    Icon(HugeIcons.Delete01, null)
                    Spacer(Modifier.size(8.dp))
                    Text(ctx.getString(R.string.multi_key_delete_errors))
                }
            }
        }

        if (showAdd) {
            AddKeysSheet(
                onDismiss = { showAdd = false },
                onAdd = { newKeys ->
                    val existingSet = apiKeys.map { it.key.trim() }.toSet()
                    val unique = newKeys.filter { it.trim() !in existingSet && it.isNotBlank() }.distinct()
                    if (unique.isNotEmpty()) {
                        val added = unique.map { ApiKeyConfig(key = it.trim(), name = "Key ${apiKeys.size + 1}") }
                        updateKeys(apiKeys + added)
                        Toast.makeText(ctx, ctx.getString(R.string.multi_key_imported, added.size), Toast.LENGTH_SHORT).show()
                    }
                    showAdd = false
                }
            )
        }
    }
    }
}

@Composable
private fun IosCard(children: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { children() }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge,
            color = if (color != Color.Unspecified) color else Color.Unspecified)
    }
}

@Composable
private fun StrategyRow(strategy: LoadBalanceStrategy, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val label = when (strategy) {
        LoadBalanceStrategy.RANDOM -> ctx.getString(R.string.multi_key_strategy_label_random)
        LoadBalanceStrategy.ROUND_ROBIN -> ctx.getString(R.string.multi_key_strategy_label_round_robin)
        LoadBalanceStrategy.LEAST_USED -> ctx.getString(R.string.multi_key_strategy_label_least_used)
        LoadBalanceStrategy.PRIORITY_FIRST -> ctx.getString(R.string.multi_key_strategy_label_priority)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(ctx.getString(R.string.multi_key_strategy), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategySheet(
    current: LoadBalanceStrategy,
    onSelect: (LoadBalanceStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("轮换策略", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
            val pairs = listOf(
                LoadBalanceStrategy.ROUND_ROBIN to "轮询 — 依次轮流使用每个 Key",
                LoadBalanceStrategy.RANDOM to "随机 — 随机选择一个 Key",
                LoadBalanceStrategy.LEAST_USED to "最少使用 — 优先选调用次数最少的 Key",
                LoadBalanceStrategy.PRIORITY_FIRST to "优先级优先 — 按列表顺序优先使用前面的 Key",
            )
            pairs.forEach { (strategy, desc) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(strategy) }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(desc, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (strategy == current) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun KeyRow(
    config: ApiKeyConfig,
    onToggle: (Boolean) -> Unit,
    onEdit: (ApiKeyConfig) -> Unit,
    onTest: ((ApiKeyConfig, (Exception?) -> Unit) -> Unit)? = null,
    isDragHandle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var testingState by remember { mutableStateOf<UiState<Unit>>(UiState.Idle) }
    val statusColor = when (config.status) {
        ApiKeyStatus.ACTIVE -> Color(0xFF4CAF50)
        ApiKeyStatus.DISABLED -> Color(0xFF9E9E9E)
        ApiKeyStatus.ERROR -> Color(0xFFF44336)
        ApiKeyStatus.RATE_LIMITED -> Color(0xFFFF9800)
        ApiKeyStatus.EXHAUSTED -> Color(0xFFF44336)
    }
    val statusLabel = when (config.status) {
        ApiKeyStatus.ACTIVE -> ctx.getString(R.string.multi_key_status_active)
        ApiKeyStatus.DISABLED -> ctx.getString(R.string.multi_key_status_disabled)
        ApiKeyStatus.ERROR -> ctx.getString(R.string.multi_key_status_error)
        ApiKeyStatus.RATE_LIMITED -> ctx.getString(R.string.multi_key_status_rate_limited)
        ApiKeyStatus.EXHAUSTED -> ctx.getString(R.string.multi_key_status_exhausted)
    }
    val maskedKey = if (config.key.length > 8) "${config.key.take(4)}••••${config.key.takeLast(4)}" else config.key
    val displayName = config.name.ifBlank { maskedKey }
    var showEdit by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = statusColor.copy(alpha = 0.12f)
        ) {
            Text(statusLabel, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
        Spacer(Modifier.width(8.dp))
        Text(displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        Switch(checked = config.status == ApiKeyStatus.ACTIVE, onCheckedChange = onToggle)
        Spacer(Modifier.width(2.dp))
        // 检测按钮
        IconButton(
            onClick = {
                testingState = UiState.Loading
                onTest?.invoke(config) { error ->
                    testingState = if (error == null) UiState.Success(Unit) else UiState.Error(error)
                }
            },
            enabled = config.key.isNotBlank() && testingState !is UiState.Loading,
            modifier = Modifier.size(36.dp)
        ) {
            when (testingState) {
                is UiState.Loading -> {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                is UiState.Success -> {
                    Icon(HugeIcons.Connect, "验证成功",
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF4CAF50))
                }
                is UiState.Error -> {
                    Icon(HugeIcons.Connect, "验证失败",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
                is UiState.Idle -> {
                    Icon(HugeIcons.Connect, ctx.getString(R.string.multi_key_test),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = { showEdit = true }, modifier = Modifier.size(36.dp)) {
            Icon(HugeIcons.PencilEdit01, ctx.getString(R.string.multi_key_edit_btn), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }

    }
    if (showEdit) {
        EditKeySheet(config = config, onDismiss = { showEdit = false }, onSave = { onEdit(it); showEdit = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeysSheet(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(ctx.getString(R.string.multi_key_add), style = MaterialTheme.typography.titleMedium)
            Text(ctx.getString(R.string.multi_key_add_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text("sk-proj-xxx1\nsk-proj-xxx2\nsk-proj-xxx3") },
            )
            Button(
                onClick = {
                    val keys = text.split(Regex("[\\s,\n]+")).map { it.trim() }.filter { it.isNotBlank() }
                    if (keys.isNotEmpty()) onAdd(keys)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = text.isNotBlank()
            ) {
                Icon(HugeIcons.PlusSign, null); Spacer(Modifier.size(8.dp)); Text(ctx.getString(R.string.multi_key_add))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditKeySheet(config: ApiKeyConfig, onDismiss: () -> Unit, onSave: (ApiKeyConfig) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(config.name) }
    var key by remember { mutableStateOf(config.key) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(ctx.getString(R.string.multi_key_edit), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it },
                label = { Text(ctx.getString(R.string.multi_key_alias)) },
                placeholder = { Text(ctx.getString(R.string.multi_key_alias_placeholder)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = key, onValueChange = { key = it },
                label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onSave(config.copy(name = name.trim(), key = key.trim())) },
                modifier = Modifier.fillMaxWidth(), enabled = key.isNotBlank()) {
                Text(ctx.getString(R.string.multi_key_save))
            }
        }
    }
}
