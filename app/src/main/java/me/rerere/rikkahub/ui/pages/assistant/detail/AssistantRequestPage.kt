package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val CompatSettingsJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

private const val MergeEditorExtensionName = "SillyTavernExtension-mergeEditor"

@Serializable
private data class MergeEditorCaptureRule(
    val enabled: Boolean = true,
    val regex: String = "",
    val tag: String = "",
    val updateMode: String = "accumulate",
    val range: String = "",
)

@Serializable
private data class MergeEditorConfig(
    val user: String = "Human",
    val assistant: String = "Assistant",
    @SerialName("example_user")
    val exampleUser: String = "H",
    @SerialName("example_assistant")
    val exampleAssistant: String = "A",
    val system: String = "SYSTEM",
    val separator: String = "",
    @SerialName("separator_system")
    val separatorSystem: String = "",
    @SerialName("prefill_user")
    val prefillUser: String = "Continue the conversation.",
    @SerialName("capture_enabled")
    val captureEnabled: Boolean = true,
    @SerialName("capture_rules")
    val captureRules: List<MergeEditorCaptureRule> = emptyList(),
    @SerialName("stored_data")
    val storedData: JsonObject = buildJsonObject { },
)

@Composable
fun AssistantRequestPage(id: String) {
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
                    Text(stringResource(R.string.assistant_page_tab_request))
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
        AssistantRequestContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
internal fun AssistantRequestContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CustomHeaders(
            headers = assistant.customHeaders,
            onUpdate = {
                onUpdate(
                    assistant.copy(
                        customHeaders = it
                    )
                )
            }
        )

        HorizontalDivider()

        CustomBodies(
            customBodies = assistant.customBodies,
            onUpdate = {
                onUpdate(
                    assistant.copy(
                        customBodies = it
                    )
                )
            }
        )

        HorizontalDivider()

        CompatScriptCard(
            assistant = assistant,
            onUpdate = onUpdate,
        )
    }
}

@Composable
private fun CompatScriptCard(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val latestAssistant by rememberUpdatedState(assistant)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val invalidJsonText = stringResource(R.string.invalid_json)

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

    Card(
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_request_page_st_compat_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.assistant_request_page_st_compat_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.assistant_request_page_st_compat_requirement),
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(
                checked = assistant.stCompatScriptEnabled,
                onCheckedChange = { enabled ->
                    onUpdate(assistant.copy(stCompatScriptEnabled = enabled))
                }
            )

            if (assistant.shouldShowMergeEditorConfig()) {
                MergeEditorConfigSection(
                    assistant = assistant,
                    onUpdate = onUpdate,
                )
            }

            TextArea(
                state = scriptState,
                label = stringResource(R.string.assistant_request_page_compatibility_script),
                placeholder = stringResource(R.string.assistant_request_page_compatibility_script_placeholder),
                minLines = 8,
                maxLines = 18,
                supportedFileTypes = arrayOf(
                    "text/*",
                    "application/javascript",
                    "text/javascript",
                ),
            )

            TextArea(
                state = settingsState,
                label = stringResource(R.string.assistant_request_page_extension_settings_json),
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

@Composable
private fun MergeEditorConfigSection(
    assistant: Assistant,
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

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.assistant_request_page_merge_editor_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.assistant_request_page_merge_editor_desc, MergeEditorExtensionName),
            style = MaterialTheme.typography.bodySmall,
        )

        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_user_label),
            value = config.user,
            onValueChange = { value -> updateConfig { it.copy(user = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_assistant_label),
            value = config.assistant,
            onValueChange = { value -> updateConfig { it.copy(assistant = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_example_user_label),
            value = config.exampleUser,
            onValueChange = { value -> updateConfig { it.copy(exampleUser = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_example_assistant_label),
            value = config.exampleAssistant,
            onValueChange = { value -> updateConfig { it.copy(exampleAssistant = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_system_label),
            value = config.system,
            onValueChange = { value -> updateConfig { it.copy(system = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_separator),
            value = config.separator,
            onValueChange = { value -> updateConfig { it.copy(separator = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_system_separator),
            value = config.separatorSystem,
            onValueChange = { value -> updateConfig { it.copy(separatorSystem = value) } }
        )
        MergeEditorTextField(
            label = stringResource(R.string.assistant_request_page_prefill_user),
            value = config.prefillUser,
            onValueChange = { value -> updateConfig { it.copy(prefillUser = value) } }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.assistant_request_page_capture_enabled),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.assistant_request_page_capture_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = config.captureEnabled,
                onCheckedChange = { enabled ->
                    updateConfig { it.copy(captureEnabled = enabled) }
                }
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_request_page_capture_rules),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.assistant_request_page_capture_rules_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            config.captureRules.forEachIndexed { index, rule ->
                MergeEditorRuleCard(
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
                Text(stringResource(R.string.assistant_request_page_add_capture_rule))
            }
        }

        Text(
            text = if (config.storedData.isEmpty()) {
                stringResource(R.string.assistant_request_page_stored_data_summary_empty)
            } else {
                stringResource(
                    R.string.assistant_request_page_stored_data_summary,
                    config.storedData.keys.sorted().joinToString(", ")
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )

        TextArea(
            state = storedDataState,
            label = stringResource(R.string.assistant_request_page_stored_data_json),
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
                Text(stringResource(R.string.assistant_request_page_clear_stored_data))
            }
        }
    }
}

@Composable
private fun MergeEditorRuleCard(
    rule: MergeEditorCaptureRule,
    index: Int,
    onUpdateRule: (MergeEditorCaptureRule) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.assistant_request_page_rule_title, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_request_page_enabled),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateRule(rule.copy(enabled = enabled))
                        }
                    )
                }
            }

            MergeEditorTextField(
                label = stringResource(R.string.assistant_request_page_regex),
                value = rule.regex,
                onValueChange = { value -> onUpdateRule(rule.copy(regex = value)) },
                placeholder = "/pattern/flags",
            )
            MergeEditorTextField(
                label = stringResource(R.string.assistant_request_page_tag),
                value = rule.tag,
                onValueChange = { value -> onUpdateRule(rule.copy(tag = value)) },
                placeholder = "<tag>",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = rule.range,
                    onValueChange = { value -> onUpdateRule(rule.copy(range = value)) },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_request_page_range)) },
                    placeholder = { Text("+1,+3~+5,-2") },
                )
                OutlinedTextField(
                    value = rule.updateMode,
                    onValueChange = { value ->
                            onUpdateRule(rule.copy(updateMode = value.ifBlank { "accumulate" }))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_request_page_mode)) },
                    placeholder = { Text("accumulate / replace") },
                )
            }

            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.assistant_request_page_delete_rule))
            }
        }
    }
}

@Composable
private fun MergeEditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder) }
        } else {
            null
        }
    )
}

private fun JsonObject.toPrettyCompatJson(): String {
    return CompatSettingsJson.encodeToString(this)
}

private fun String.parseCompatSettingsJson(): JsonObject {
    if (isBlank()) return buildJsonObject { }
    return CompatSettingsJson.parseToJsonElement(this).jsonObject
}

private fun Assistant.shouldShowMergeEditorConfig(): Boolean {
    return stCompatScriptSource.contains(MergeEditorExtensionName) ||
        stCompatExtensionSettings.containsKey(MergeEditorExtensionName)
}

private fun Assistant.readMergeEditorConfig(): MergeEditorConfig {
    val raw = runCatching { stCompatExtensionSettings[MergeEditorExtensionName]?.jsonObject }.getOrNull()
        ?: return MergeEditorConfig()
    return runCatching {
        CompatSettingsJson.decodeFromJsonElement(MergeEditorConfig.serializer(), raw)
    }.getOrElse {
        MergeEditorConfig()
    }
}

private fun Assistant.withMergeEditorConfig(config: MergeEditorConfig): Assistant {
    val updated = stCompatExtensionSettings.toMutableMap().apply {
        put(
            MergeEditorExtensionName,
            CompatSettingsJson.encodeToJsonElement(MergeEditorConfig.serializer(), config)
        )
    }
    return copy(stCompatExtensionSettings = JsonObject(updated))
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
