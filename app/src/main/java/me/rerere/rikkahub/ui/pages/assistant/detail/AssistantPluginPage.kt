package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantPluginPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("自定义插件")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        AssistantPluginContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) },
        )
    }
}

@Composable
private fun AssistantPluginContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val latestAssistant by rememberUpdatedState(assistant)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val invalidJsonText = stringResource(R.string.invalid_json)
    val detectedPlugins = remember(assistant.stCompatScriptSource, assistant.stCompatExtensionSettings) {
        detectStCompatPlugins(assistant)
    }

    val scriptState = rememberTextFieldState(initialText = assistant.stCompatScriptSource)
    var lastExternalScript by remember { mutableStateOf(assistant.stCompatScriptSource) }
    var lastDispatchedScript by remember { mutableStateOf(assistant.stCompatScriptSource) }
    val scriptText = scriptState.text.toString()

    val initialSettingsText = remember(assistant.stCompatExtensionSettings) {
        assistant.stCompatExtensionSettings.toPrettyCompatJson()
    }
    val settingsState = rememberTextFieldState(initialText = initialSettingsText)
    var lastExternalSettings by remember { mutableStateOf(initialSettingsText) }
    var lastDispatchedSettings by remember { mutableStateOf(initialSettingsText) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val settingsText = settingsState.text.toString()

    var showScriptEditor by remember(assistant.stCompatScriptSource) {
        mutableStateOf(assistant.stCompatScriptSource.isNotBlank())
    }
    var showRawJson by remember { mutableStateOf(false) }

    LaunchedEffect(assistant.stCompatScriptSource) {
        val currentText = scriptState.text.toString()
        val shouldSyncText =
            currentText == lastExternalScript ||
                assistant.stCompatScriptSource != lastDispatchedScript ||
                currentText == assistant.stCompatScriptSource
        lastExternalScript = assistant.stCompatScriptSource

        if (shouldSyncText && currentText != assistant.stCompatScriptSource) {
            scriptState.edit {
                replace(0, length, assistant.stCompatScriptSource)
            }
        }
        if (shouldSyncText) {
            lastDispatchedScript = assistant.stCompatScriptSource
        }
    }
    LaunchedEffect(scriptText) {
        if (
            scriptText == latestAssistant.stCompatScriptSource ||
            scriptText == lastDispatchedScript
        ) {
            return@LaunchedEffect
        }
        delay(400)
        val latestText = scriptState.text.toString()
        if (
            latestText == scriptText &&
            latestText != latestAssistant.stCompatScriptSource &&
            latestText != lastDispatchedScript
        ) {
            lastDispatchedScript = latestText
            latestOnUpdate(
                latestAssistant.copy(
                    stCompatScriptSource = latestText,
                )
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val currentText = scriptState.text.toString()
            if (
                currentText != latestAssistant.stCompatScriptSource &&
                currentText != lastDispatchedScript
            ) {
                lastDispatchedScript = currentText
                latestOnUpdate(
                    latestAssistant.copy(
                        stCompatScriptSource = currentText,
                    )
                )
            }
        }
    }

    LaunchedEffect(assistant.stCompatExtensionSettings) {
        val externalText = assistant.stCompatExtensionSettings.toPrettyCompatJson()
        val currentText = settingsState.text.toString()
        val shouldSyncText =
            currentText == lastExternalSettings ||
                externalText != lastDispatchedSettings ||
                currentText == externalText
        lastExternalSettings = externalText

        if (shouldSyncText && currentText != externalText) {
            settingsState.edit {
                replace(0, length, externalText)
            }
        }
        if (shouldSyncText) {
            lastDispatchedSettings = externalText
            settingsError = null
        }
    }
    LaunchedEffect(settingsText) {
        if (
            settingsText == lastDispatchedSettings ||
            settingsText == latestAssistant.stCompatExtensionSettings.toPrettyCompatJson()
        ) {
            settingsError = null
            return@LaunchedEffect
        }
        delay(400)
        val latestText = settingsState.text.toString()
        if (latestText != settingsText || latestText == lastDispatchedSettings) {
            return@LaunchedEffect
        }
        val parsed = runCatching { latestText.parseCompatSettingsJson() }
            .getOrElse { error ->
                settingsError = error.message ?: invalidJsonText
                return@LaunchedEffect
            }
        settingsError = null
        lastDispatchedSettings = latestText
        latestOnUpdate(
            latestAssistant.copy(
                stCompatExtensionSettings = parsed,
            )
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            val currentText = settingsState.text.toString()
            if (
                currentText == lastDispatchedSettings ||
                currentText == latestAssistant.stCompatExtensionSettings.toPrettyCompatJson()
            ) {
                return@onDispose
            }
            val parsed = runCatching { currentText.parseCompatSettingsJson() }.getOrElse {
                settingsError = it.message ?: invalidJsonText
                return@onDispose
            }
            settingsError = null
            lastDispatchedSettings = currentText
            latestOnUpdate(
                latestAssistant.copy(
                    stCompatExtensionSettings = parsed,
                )
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PluginHeroCard(
            pluginCount = detectedPlugins.size,
            settingsCount = assistant.stCompatExtensionSettings.size,
            enabled = assistant.stCompatScriptEnabled,
        )

        SectionHeader(
            title = "脚本引擎",
            subtitle = "兼容层会在请求发送前运行 ST 风格脚本。这里负责总开关和脚本源码。"
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ToggleRow(
                    title = "启用兼容脚本",
                    subtitle = "关闭后不会执行任何自定义插件或 ST 兼容脚本。",
                    checked = assistant.stCompatScriptEnabled,
                    onToggle = { enabled ->
                        onUpdate(assistant.copy(stCompatScriptEnabled = enabled))
                    }
                )

                ExpandableRow(
                    title = "脚本源码",
                    subtitle = if (assistant.stCompatScriptSource.isBlank()) {
                        "当前还没有脚本，可直接粘贴插件源码。"
                    } else {
                        "已载入 ${assistant.stCompatScriptSource.length} 个字符，点击展开编辑。"
                    },
                    expanded = showScriptEditor,
                    onToggle = { showScriptEditor = !showScriptEditor },
                )
                AnimatedVisibility(showScriptEditor) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextArea(
                            state = scriptState,
                            label = "兼容脚本",
                            placeholder = "粘贴 ST 插件脚本或兼容脚本",
                            minLines = 8,
                            maxLines = 18,
                            supportedFileTypes = arrayOf(
                                "text/*",
                                "application/javascript",
                                "text/javascript",
                            ),
                        )
                        Text(
                            text = "脚本里声明的 extensionName 会自动出现在下方插件区；已知插件会出现专用设置面板。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionHeader(
            title = "活动插件",
            subtitle = "从脚本源码和现有设置里自动识别插件。已知插件使用可视化表单，未知插件保留通用 JSON 面板。"
        )
        if (detectedPlugins.isEmpty()) {
            EmptyPluginState()
        } else {
            detectedPlugins.forEach { plugin ->
                when (plugin.kind) {
                    DetectedStCompatPluginKind.MERGE_EDITOR -> MergeEditorPluginPanel(
                        assistant = assistant,
                        plugin = plugin,
                        onUpdate = onUpdate,
                    )

                    DetectedStCompatPluginKind.GENERIC -> GenericPluginPanel(
                        assistant = assistant,
                        plugin = plugin,
                        onUpdate = onUpdate,
                    )
                }
            }
        }

        SectionHeader(
            title = "高级",
            subtitle = "需要排错、批量迁移或处理未知字段时，再用整个扩展设置 JSON。"
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExpandableRow(
                    title = "原始扩展设置 JSON",
                    subtitle = "直接编辑 assistant.stCompatExtensionSettings。",
                    expanded = showRawJson,
                    onToggle = { showRawJson = !showRawJson },
                )
                AnimatedVisibility(showRawJson) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextArea(
                            state = settingsState,
                            label = "扩展设置 JSON",
                            placeholder = "{}",
                            minLines = 6,
                            maxLines = 14,
                            supportedFileTypes = arrayOf(
                                "application/json",
                                "text/*",
                            ),
                        )
                        settingsError?.takeIf { it.isNotBlank() }?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginHeroCard(
    pluginCount: Int,
    settingsCount: Int,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(10.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = HugeIcons.Puzzle,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ST 兼容插件工作台",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (enabled) {
                                "当前引擎已启用，脚本会参与每次请求前的消息整形。"
                            } else {
                                "当前引擎已关闭，所有插件和兼容脚本都不会运行。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroStatPill(
                        label = "检测到插件",
                        value = pluginCount.toString(),
                    )
                    HeroStatPill(
                        label = "设置键",
                        value = settingsCount.toString(),
                    )
                    HeroStatPill(
                        label = "引擎状态",
                        value = if (enabled) "ON" else "OFF",
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStatPill(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title)
        },
        supportingContent = {
            Text(subtitle)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onToggle(!checked) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        )
    )
}

@Composable
private fun ExpandableRow(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title)
        },
        supportingContent = {
            Text(subtitle)
        },
        trailingContent = {
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onToggle),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        )
    )
}

@Composable
private fun EmptyPluginState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "还没有识别到插件",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "把插件脚本粘贴到上面的脚本源码后，这里会自动列出 extensionName，并按插件类型显示设置面板。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MergeEditorPluginPanel(
    assistant: Assistant,
    plugin: DetectedStCompatPlugin,
    onUpdate: (Assistant) -> Unit,
) {
    val config = assistant.readMergeEditorConfig()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestConfig by rememberUpdatedState(config)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val invalidJsonText = stringResource(R.string.invalid_json)

    val initialStoredDataText = remember(config.storedData) {
        config.storedData.toPrettyCompatJson()
    }
    val storedDataState = rememberTextFieldState(initialText = initialStoredDataText)
    var lastExternalStoredData by remember { mutableStateOf(initialStoredDataText) }
    var lastDispatchedStoredData by remember { mutableStateOf(initialStoredDataText) }
    var storedDataError by remember { mutableStateOf<String?>(null) }
    val storedDataText = storedDataState.text.toString()
    var showStoredData by remember(config.storedData) { mutableStateOf(config.storedData.isNotEmpty()) }
    var showPluginJson by remember { mutableStateOf(false) }

    fun updateConfig(transform: (MergeEditorConfig) -> MergeEditorConfig) {
        onUpdate(assistant.withMergeEditorConfig(transform(config)))
    }

    LaunchedEffect(config.storedData) {
        val externalText = config.storedData.toPrettyCompatJson()
        val currentText = storedDataState.text.toString()
        val shouldSyncText =
            currentText == lastExternalStoredData ||
                externalText != lastDispatchedStoredData ||
                currentText == externalText
        lastExternalStoredData = externalText

        if (shouldSyncText && currentText != externalText) {
            storedDataState.edit {
                replace(0, length, externalText)
            }
        }
        if (shouldSyncText) {
            lastDispatchedStoredData = externalText
            storedDataError = null
        }
    }
    LaunchedEffect(storedDataText) {
        if (
            storedDataText == lastDispatchedStoredData ||
            storedDataText == latestConfig.storedData.toPrettyCompatJson()
        ) {
            storedDataError = null
            return@LaunchedEffect
        }
        delay(400)
        val latestText = storedDataState.text.toString()
        if (latestText != storedDataText || latestText == lastDispatchedStoredData) {
            return@LaunchedEffect
        }
        val parsed = runCatching { latestText.parseCompatSettingsJson() }
            .getOrElse { error ->
                storedDataError = error.message ?: invalidJsonText
                return@LaunchedEffect
            }
        storedDataError = null
        lastDispatchedStoredData = latestText
        latestOnUpdate(
            latestAssistant.withMergeEditorConfig(
                latestConfig.copy(storedData = parsed)
            )
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            val currentText = storedDataState.text.toString()
            if (
                currentText == lastDispatchedStoredData ||
                currentText == latestConfig.storedData.toPrettyCompatJson()
            ) {
                return@onDispose
            }
            val parsed = runCatching { currentText.parseCompatSettingsJson() }.getOrElse {
                storedDataError = it.message ?: invalidJsonText
                return@onDispose
            }
            storedDataError = null
            lastDispatchedStoredData = currentText
            latestOnUpdate(
                latestAssistant.withMergeEditorConfig(
                    latestConfig.copy(storedData = parsed)
                )
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PluginCardHeader(plugin = plugin)

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MergeEditorTextField(
                    label = "User",
                    value = config.user,
                    onValueChange = { value -> updateConfig { it.copy(user = value) } },
                    modifier = Modifier.weight(1f),
                )
                MergeEditorTextField(
                    label = "Assistant",
                    value = config.assistant,
                    onValueChange = { value -> updateConfig { it.copy(assistant = value) } },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MergeEditorTextField(
                    label = "Example User",
                    value = config.exampleUser,
                    onValueChange = { value -> updateConfig { it.copy(exampleUser = value) } },
                    modifier = Modifier.weight(1f),
                )
                MergeEditorTextField(
                    label = "Example Assistant",
                    value = config.exampleAssistant,
                    onValueChange = { value -> updateConfig { it.copy(exampleAssistant = value) } },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MergeEditorTextField(
                    label = "Separator",
                    value = config.separator,
                    onValueChange = { value -> updateConfig { it.copy(separator = value) } },
                    modifier = Modifier.weight(1f),
                )
                MergeEditorTextField(
                    label = "System Separator",
                    value = config.separatorSystem,
                    onValueChange = { value -> updateConfig { it.copy(separatorSystem = value) } },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MergeEditorTextField(
                    label = "System Label",
                    value = config.system,
                    onValueChange = { value -> updateConfig { it.copy(system = value) } },
                    modifier = Modifier.weight(1f),
                )
                MergeEditorTextField(
                    label = "Prefill User",
                    value = config.prefillUser,
                    onValueChange = { value -> updateConfig { it.copy(prefillUser = value) } },
                    modifier = Modifier.weight(1f),
                )
            }

            ToggleRow(
                title = "启用数据捕获",
                subtitle = "按规则提取文本并写入 stored_data，供标签替换复用。",
                checked = config.captureEnabled,
                onToggle = { enabled ->
                    updateConfig { it.copy(captureEnabled = enabled) }
                },
            )

            HorizontalDivider()

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "捕获规则",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "规则会在合并前对内容跑正则，支持范围筛选、accumulate / replace 和标签替换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                config.captureRules.forEachIndexed { index, rule ->
                    MergeEditorRuleBlock(
                        rule = rule,
                        index = index,
                        onUpdateRule = { updatedRule ->
                            updateConfig {
                                it.copy(
                                    captureRules = it.captureRules.updated(index, updatedRule)
                                )
                            }
                        },
                        onDelete = {
                            updateConfig {
                                it.copy(
                                    captureRules = it.captureRules.removeIndex(index)
                                )
                            }
                        }
                    )
                }

                Button(
                    onClick = {
                        updateConfig {
                            it.copy(captureRules = it.captureRules + MergeEditorCaptureRule())
                        }
                    }
                ) {
                    Text("添加捕获规则")
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "存储数据",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (config.storedData.isEmpty()) {
                            "当前还没有持久化标签数据。"
                        } else {
                            "已保存标签：${config.storedData.keys.sorted().joinToString(", ")}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExpandableRow(
                        title = "stored_data JSON",
                        subtitle = "需要手工修正标签内容时再展开。",
                        expanded = showStoredData,
                        onToggle = { showStoredData = !showStoredData },
                    )
                    AnimatedVisibility(showStoredData) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextArea(
                                state = storedDataState,
                                label = "stored_data",
                                placeholder = "{}",
                                minLines = 4,
                                maxLines = 10,
                                supportedFileTypes = arrayOf(
                                    "application/json",
                                    "text/*",
                                ),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                storedDataError?.takeIf { it.isNotBlank() }?.let { error ->
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val empty = buildJsonObject { }
                                        val emptyText = empty.toPrettyCompatJson()
                                        storedDataError = null
                                        lastDispatchedStoredData = emptyText
                                        lastExternalStoredData = emptyText
                                        storedDataState.edit {
                                            replace(0, length, emptyText)
                                        }
                                        updateConfig { it.copy(storedData = empty) }
                                    }
                                ) {
                                    Text("清空 stored_data")
                                }
                            }
                        }
                    }
                }
            }

            ExpandableRow(
                title = "插件原始 JSON",
                subtitle = "可视化表单之外仍可直接编辑该插件的整段配置。",
                expanded = showPluginJson,
                onToggle = { showPluginJson = !showPluginJson },
            )
            AnimatedVisibility(showPluginJson) {
                GenericPluginJsonEditor(
                    assistant = assistant,
                    pluginKey = plugin.key,
                    label = "${plugin.displayName} JSON",
                    onUpdate = onUpdate,
                )
            }
        }
    }
}

@Composable
private fun GenericPluginPanel(
    assistant: Assistant,
    plugin: DetectedStCompatPlugin,
    onUpdate: (Assistant) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PluginCardHeader(plugin = plugin)
            Text(
                text = "这个插件暂时没有专用表单。可以直接编辑它自己的 JSON 设置，或在下方高级区修改整包扩展配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GenericPluginJsonEditor(
                assistant = assistant,
                pluginKey = plugin.key,
                label = "${plugin.displayName} JSON",
                onUpdate = onUpdate,
            )
        }
    }
}

@Composable
private fun PluginCardHeader(
    plugin: DetectedStCompatPlugin,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(
                    modifier = Modifier.padding(10.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = HugeIcons.Code,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PluginMetaPill(
                label = plugin.key,
                highlighted = plugin.detectedInScript,
            )
            PluginMetaPill(
                label = if (plugin.hasSettings) "已配置" else "未配置",
                highlighted = plugin.hasSettings,
            )
        }
    }
}

@Composable
private fun PluginMetaPill(
    label: String,
    highlighted: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun GenericPluginJsonEditor(
    assistant: Assistant,
    pluginKey: String,
    label: String,
    onUpdate: (Assistant) -> Unit,
) {
    val latestAssistant by rememberUpdatedState(assistant)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val invalidJsonText = stringResource(R.string.invalid_json)

    val externalJson = remember(assistant.stCompatExtensionSettings, pluginKey) {
        assistant.readCompatPluginSettings(pluginKey)
    }
    val initialText = remember(externalJson) { externalJson.toPrettyCompatJson() }
    val state = rememberTextFieldState(initialText = initialText)
    var lastExternalText by remember { mutableStateOf(initialText) }
    var lastDispatchedText by remember { mutableStateOf(initialText) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val currentText = state.text.toString()

    LaunchedEffect(externalJson) {
        val externalText = externalJson.toPrettyCompatJson()
        val text = state.text.toString()
        val shouldSyncText =
            text == lastExternalText ||
                externalText != lastDispatchedText ||
                text == externalText
        lastExternalText = externalText

        if (shouldSyncText && text != externalText) {
            state.edit {
                replace(0, length, externalText)
            }
        }
        if (shouldSyncText) {
            lastDispatchedText = externalText
            errorText = null
        }
    }
    LaunchedEffect(currentText) {
        if (
            currentText == lastDispatchedText ||
            currentText == latestAssistant.readCompatPluginSettings(pluginKey).toPrettyCompatJson()
        ) {
            errorText = null
            return@LaunchedEffect
        }
        delay(400)
        val latestText = state.text.toString()
        if (latestText != currentText || latestText == lastDispatchedText) {
            return@LaunchedEffect
        }
        val parsed = runCatching { latestText.parseCompatSettingsJson() }
            .getOrElse {
                errorText = it.message ?: invalidJsonText
                return@LaunchedEffect
            }
        errorText = null
        lastDispatchedText = latestText
        latestOnUpdate(latestAssistant.withCompatPluginSettings(pluginKey, parsed))
    }
    DisposableEffect(Unit) {
        onDispose {
            val text = state.text.toString()
            if (
                text == lastDispatchedText ||
                text == latestAssistant.readCompatPluginSettings(pluginKey).toPrettyCompatJson()
            ) {
                return@onDispose
            }
            val parsed = runCatching { text.parseCompatSettingsJson() }.getOrElse {
                errorText = it.message ?: invalidJsonText
                return@onDispose
            }
            errorText = null
            lastDispatchedText = text
            latestOnUpdate(latestAssistant.withCompatPluginSettings(pluginKey, parsed))
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextArea(
            state = state,
            label = label,
            placeholder = "{}",
            minLines = 4,
            maxLines = 10,
            supportedFileTypes = arrayOf(
                "application/json",
                "text/*",
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            errorText?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(
                onClick = {
                    val empty = buildJsonObject { }
                    val emptyText = empty.toPrettyCompatJson()
                    errorText = null
                    lastDispatchedText = emptyText
                    lastExternalText = emptyText
                    state.edit {
                        replace(0, length, emptyText)
                    }
                    latestOnUpdate(latestAssistant.withCompatPluginSettings(pluginKey, empty))
                }
            ) {
                Text("清空")
            }
        }
    }
}

@Composable
private fun MergeEditorRuleBlock(
    rule: MergeEditorCaptureRule,
    index: Int,
    onUpdateRule: (MergeEditorCaptureRule) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "规则 ${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (rule.enabled) "已启用" else "已停用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateRule(rule.copy(enabled = enabled))
                        }
                    )
                    TextButton(onClick = onDelete) {
                        Text("删除")
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MergeEditorTextField(
                    label = "Regex",
                    value = rule.regex,
                    onValueChange = { value -> onUpdateRule(rule.copy(regex = value)) },
                    placeholder = "/pattern/flags",
                    modifier = Modifier.weight(1f),
                )
                MergeEditorTextField(
                    label = "Tag",
                    value = rule.tag,
                    onValueChange = { value -> onUpdateRule(rule.copy(tag = value)) },
                    placeholder = "<tag>",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MergeEditorTextField(
                    label = "Range",
                    value = rule.range,
                    onValueChange = { value -> onUpdateRule(rule.copy(range = value)) },
                    placeholder = "+1,+3~+5,-2",
                    modifier = Modifier.weight(1f),
                )
                MergeEditorTextField(
                    label = "Mode",
                    value = rule.updateMode,
                    onValueChange = { value ->
                        onUpdateRule(rule.copy(updateMode = value.ifBlank { "accumulate" }))
                    },
                    placeholder = "accumulate / replace",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MergeEditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder) }
        } else {
            null
        },
        singleLine = true,
    )
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> {
    return mapIndexed { currentIndex, item ->
        if (currentIndex == index) value else item
    }
}

private fun <T> List<T>.removeIndex(index: Int): List<T> {
    return filterIndexed { currentIndex, _ ->
        currentIndex != index
    }
}
