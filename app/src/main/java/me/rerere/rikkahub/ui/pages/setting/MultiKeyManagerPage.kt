package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ApiKeyConfig
import me.rerere.ai.provider.ApiKeyStatus
import me.rerere.ai.provider.KeyManagementConfig
import me.rerere.ai.provider.LoadBalanceStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 多 Key 管理页面入口（从导航路由调用）
 */
@Composable
fun MultiKeyManagerContent(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsState()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return

    MultiKeyManagerContent(
        provider = provider,
        onEdit = { newProvider ->
            vm.updateSettings(
                settings.copy(
                    providers = settings.providers.map {
                        if (it.id == newProvider.id) newProvider else it
                    }
                )
            )
        },
        onBack = { navController.popBackStack() }
    )
}

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ApiKeyConfig
import me.rerere.ai.provider.ApiKeyStatus
import me.rerere.ai.provider.KeyManagementConfig
import me.rerere.ai.provider.LoadBalanceStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.extendColors

/**
 * 多 Key 管理页面
 *
 * 参考 Kelivo 的 MultiKeyManagerPage 实现
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiKeyManagerContent(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onBack: () -> Unit,
) {
    val toaster = LocalToaster.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showStrategyDialog by remember { mutableStateOf(false) }
    var localProvider by remember(provider) { mutableStateOf(provider) }

    // 获取状态统计
    val apiKeys = localProvider.apiKeys
    val activeCount = apiKeys.count { it.status == ApiKeyStatus.ACTIVE && it.isEnabled }
    val errorCount = apiKeys.count { it.status == ApiKeyStatus.ERROR }
    val totalCount = apiKeys.size

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClick = onBack) },
                title = { Text(stringResource(R.string.multi_key_page_title)) },
                actions = {
                    IconButton(onClick = { showStrategyDialog = true }) {
                        Icon(Lucide.KeyRound, stringResource(R.string.multi_key_page_strategy))
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Lucide.Plus, stringResource(R.string.multi_key_page_add))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 统计概览
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.multi_key_page_overview),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            label = stringResource(R.string.multi_key_page_total),
                            value = totalCount.toString()
                        )
                        StatItem(
                            label = stringResource(R.string.multi_key_page_active),
                            value = activeCount.toString(),
                            color = MaterialTheme.extendColors.green6
                        )
                        StatItem(
                            label = stringResource(R.string.multi_key_page_error),
                            value = errorCount.toString(),
                            color = if (errorCount > 0) MaterialTheme.extendColors.red6 else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // 策略显示
                    Text(
                        text = "${stringResource(R.string.multi_key_page_strategy)}: ${
                            strategyDisplayName(localProvider.keyManagement.strategy)
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Key 列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(apiKeys) { index, keyConfig ->
                    ApiKeyCard(
                        keyConfig = keyConfig,
                        index = index,
                        onToggle = {
                            val updatedKeys = apiKeys.toMutableList().apply {
                                set(index, keyConfig.copy(isEnabled = !keyConfig.isEnabled, updatedAt = System.currentTimeMillis()))
                            }
                            localProvider = updateProviderKeys(localProvider, updatedKeys)
                        },
                        onDelete = {
                            val updatedKeys = apiKeys.toMutableList().apply {
                                removeAt(index)
                            }
                            localProvider = updateProviderKeys(localProvider, updatedKeys)
                        },
                        onEdit = { newName ->
                            val updatedKeys = apiKeys.toMutableList().apply {
                                set(index, keyConfig.copy(name = newName, updatedAt = System.currentTimeMillis()))
                            }
                            localProvider = updateProviderKeys(localProvider, updatedKeys)
                        }
                    )
                }
            }

            // 保存按钮
            Button(
                onClick = {
                    onEdit(localProvider)
                    toaster.show(
                        "多 Key 配置已保存",
                        type = ToastType.Success
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }

    // 添加 Key 对话框
    if (showAddDialog) {
        AddKeyDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { keyValue, keyName, priority ->
                val now = System.currentTimeMillis()
                val newKey = ApiKeyConfig(
                    id = ApiKeyConfig.generateId(),
                    key = keyValue.trim(),
                    name = keyName.takeIf { it.isNotBlank() },
                    priority = priority,
                    createdAt = now,
                    updatedAt = now,
                )
                localProvider = updateProviderKeys(
                    localProvider,
                    localProvider.apiKeys + newKey
                )
                showAddDialog = false
            }
        )
    }

    // 策略选择对话框
    if (showStrategyDialog) {
        StrategyDialog(
            currentStrategy = localProvider.keyManagement.strategy,
            maxFailures = localProvider.keyManagement.maxFailuresBeforeDisable,
            recoveryMinutes = localProvider.keyManagement.failureRecoveryTimeMinutes,
            onDismiss = { showStrategyDialog = false },
            onConfirm = { strategy, maxFailures, recoveryMinutes ->
                localProvider = localProvider.copyProvider(
                    keyManagement = KeyManagementConfig(
                        strategy = strategy,
                        maxFailuresBeforeDisable = maxFailures,
                        failureRecoveryTimeMinutes = recoveryMinutes,
                    )
                )
                showStrategyDialog = false
            }
        )
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ApiKeyCard(
    keyConfig: ApiKeyConfig,
    index: Int,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (String) -> Unit,
) {
    var editingName by remember(keyConfig.name) { mutableStateOf(keyConfig.name ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 状态指示器
                val statusColor = when (keyConfig.status) {
                    ApiKeyStatus.ACTIVE -> MaterialTheme.extendColors.green6
                    ApiKeyStatus.ERROR -> MaterialTheme.extendColors.red6
                    ApiKeyStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ApiKeyStatus.RATE_LIMITED -> MaterialTheme.extendColors.orange6
                }

                Icon(
                    Lucide.KeyRound,
                    contentDescription = null,
                    tint = if (keyConfig.isEnabled) statusColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(8.dp))

                // Key 名称/值
                Column(modifier = Modifier.weight(1f)) {
                    // 名称
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = {
                            editingName = it
                            onEdit(it)
                        },
                        placeholder = {
                            Text(
                                stringResource(R.string.multi_key_page_key_name_placeholder, index + 1),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(4.dp))

                    // Key 值（脱敏显示）
                    Text(
                        text = maskApiKey(keyConfig.key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )

                    // 状态和优先级信息
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(keyConfig.status)
                        if (keyConfig.priority != 5) {
                            Text(
                                text = "P${keyConfig.priority}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (keyConfig.usage.totalRequests > 0) {
                            Text(
                                text = "${keyConfig.usage.totalRequests} req",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (keyConfig.lastError != null) {
                            Text(
                                text = keyConfig.lastError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.extendColors.red6,
                                maxLines = 1
                            )
                        }
                    }
                }

                // 操作按钮
                IconButton(onClick = onToggle) {
                    Icon(
                        if (keyConfig.isEnabled) Lucide.KeyRound else Lucide.KeyRound,
                        contentDescription = if (keyConfig.isEnabled) "Disable" else "Enable",
                        tint = if (keyConfig.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }

                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.extendColors.red6
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.multi_key_page_delete_confirm)) },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.delete))
                }
            }
        )
    }
}

@Composable
private fun StatusBadge(status: ApiKeyStatus) {
    val (text, color) = when (status) {
        ApiKeyStatus.ACTIVE -> "Active" to MaterialTheme.extendColors.green6
        ApiKeyStatus.ERROR -> "Error" to MaterialTheme.extendColors.red6
        ApiKeyStatus.DISABLED -> "Disabled" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        ApiKeyStatus.RATE_LIMITED -> "Rate Limited" to MaterialTheme.extendColors.orange6
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
private fun AddKeyDialog(
    onDismiss: () -> Unit,
    onAdd: (key: String, name: String, priority: Int) -> Unit
) {
    var keyValue by remember { mutableStateOf("") }
    var keyName by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("5") }
    var isImportMode by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isImportMode) stringResource(R.string.multi_key_page_import_keys)
                else stringResource(R.string.multi_key_page_add_key)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 切换批量导入模式
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        selected = !isImportMode,
                        onClick = { isImportMode = false },
                        label = { Text(stringResource(R.string.multi_key_page_single_add)) }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        selected = isImportMode,
                        onClick = { isImportMode = true },
                        label = { Text(stringResource(R.string.multi_key_page_batch_import)) }
                    )
                }

                if (isImportMode) {
                    // 批量导入模式
                    Text(
                        text = stringResource(R.string.multi_key_page_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = keyValue,
                        onValueChange = { keyValue = it },
                        label = { Text(stringResource(R.string.multi_key_page_api_keys)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        maxLines = 6,
                        placeholder = {
                            Text(stringResource(R.string.multi_key_page_import_placeholder))
                        }
                    )
                } else {
                    // 单个添加
                    OutlinedTextField(
                        value = keyValue,
                        onValueChange = { keyValue = it },
                        label = { Text(stringResource(R.string.multi_key_page_api_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.multi_key_page_key_placeholder))
                        }
                    )

                    OutlinedTextField(
                        value = keyName,
                        onValueChange = { keyName = it },
                        label = { Text(stringResource(R.string.multi_key_page_key_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.multi_key_page_key_name_hint))
                        }
                    )

                    OutlinedTextField(
                        value = priority,
                        onValueChange = {
                            if (it.isEmpty() || (it.toIntOrNull() != null && it.toInt() in 1..10)) {
                                priority = it
                            }
                        },
                        label = { Text(stringResource(R.string.multi_key_page_priority)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(stringResource(R.string.multi_key_page_priority_hint))
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isImportMode) {
                        // 批量导入：按行/逗号分隔
                        val keys = keyValue.split(Regex("[\\n\\r,]+"))
                            .map { it.trim() }
                            .filter { it.isNotBlank() && it.length > 5 }
                        keys.forEach { key ->
                            onAdd(key, "", 5)
                        }
                    } else {
                        if (keyValue.isNotBlank()) {
                            onAdd(
                                keyValue.trim(),
                                keyName.trim(),
                                priority.toIntOrNull() ?: 5
                            )
                        }
                    }
                },
                enabled = keyValue.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategyDialog(
    currentStrategy: LoadBalanceStrategy,
    maxFailures: Int,
    recoveryMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (LoadBalanceStrategy, Int, Int) -> Unit,
) {
    var selectedStrategy by remember { mutableStateOf(currentStrategy) }
    var maxFailuresStr by remember { mutableStateOf(maxFailures.toString()) }
    var recoveryStr by remember { mutableStateOf(recoveryMinutes.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multi_key_page_strategy_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 负载均衡策略
                Text(
                    text = stringResource(R.string.multi_key_page_load_balance),
                    style = MaterialTheme.typography.titleSmall
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LoadBalanceStrategy.entries.forEach { strategy ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strategyDisplayName(strategy),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = strategyDescription(strategy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.material3.RadioButton(
                                selected = selectedStrategy == strategy,
                                onClick = { selectedStrategy = strategy }
                            )
                        }
                    }
                }

                // 失败重试配置
                Text(
                    text = stringResource(R.string.multi_key_page_failure_config),
                    style = MaterialTheme.typography.titleSmall
                )

                OutlinedTextField(
                    value = maxFailuresStr,
                    onValueChange = {
                        if (it.isEmpty() || (it.toIntOrNull() != null && it.toInt() > 0)) {
                            maxFailuresStr = it
                        }
                    },
                    label = { Text(stringResource(R.string.multi_key_page_max_failures)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(stringResource(R.string.multi_key_page_max_failures_hint))
                    }
                )

                OutlinedTextField(
                    value = recoveryStr,
                    onValueChange = {
                        if (it.isEmpty() || (it.toIntOrNull() != null && it.toInt() > 0)) {
                            recoveryStr = it
                        }
                    },
                    label = { Text(stringResource(R.string.multi_key_page_recovery_time)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(stringResource(R.string.multi_key_page_recovery_time_hint))
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        selectedStrategy,
                        maxFailuresStr.toIntOrNull() ?: 3,
                        recoveryStr.toIntOrNull() ?: 5,
                    )
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

/** 更新 ProviderSetting 中的 apiKeys */
private fun updateProviderKeys(
    provider: ProviderSetting,
    newKeys: List<ApiKeyConfig>
): ProviderSetting {
    return when (provider) {
        is ProviderSetting.OpenAI -> provider.copy(apiKeys = newKeys)
        is ProviderSetting.Google -> provider.copy(apiKeys = newKeys)
        is ProviderSetting.Claude -> provider.copy(apiKeys = newKeys)
    }
}

/** 掩码显示 API Key */
private fun maskApiKey(key: String): String {
    return when {
        key.length <= 8 -> "****"
        key.length <= 12 -> "${key.take(4)}****${key.takeLast(4)}"
        else -> "${key.take(6)}****${key.takeLast(6)}"
    }
}

/** 策略显示名称 */
private fun strategyDisplayName(strategy: LoadBalanceStrategy): String {
    return when (strategy) {
        LoadBalanceStrategy.ROUND_ROBIN -> "轮询 (Round-Robin)"
        LoadBalanceStrategy.PRIORITY -> "优先级 (Priority)"
        LoadBalanceStrategy.LEAST_USED -> "最少使用 (Least-Used)"
        LoadBalanceStrategy.RANDOM -> "随机 (Random)"
    }
}

/** 策略描述 */
private fun strategyDescription(strategy: LoadBalanceStrategy): String {
    return when (strategy) {
        LoadBalanceStrategy.ROUND_ROBIN -> "按顺序轮流使用每个 Key"
        LoadBalanceStrategy.PRIORITY -> "优先使用优先级高的 Key（数字越小优先级越高）"
        LoadBalanceStrategy.LEAST_USED -> "优先使用调用次数最少的 Key"
        LoadBalanceStrategy.RANDOM -> "随机选择一个 Key"
    }
}
