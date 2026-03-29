package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
}

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
                settingsError = error.message ?: "Invalid JSON"
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
                settingsError = it.message ?: "Invalid JSON"
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
                text = "SillyTavern JS Compatibility",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Run a SillyTavern-style CHAT_COMPLETION_SETTINGS_READY script in Termux Node before OpenAI / Gemini / Claude text requests. This MVP is generation-only and does not recreate the original ST settings popup UI.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Requires Termux with nodejs installed: `pkg install nodejs`.",
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(
                checked = assistant.stCompatScriptEnabled,
                onCheckedChange = { enabled ->
                    onUpdate(assistant.copy(stCompatScriptEnabled = enabled))
                }
            )

            TextArea(
                state = scriptState,
                label = "Compatibility Script",
                placeholder = "Paste or import a SillyTavern compatibility script",
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
                label = "extensionSettings JSON",
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

private fun JsonObject.toPrettyCompatJson(): String {
    return CompatSettingsJson.encodeToString(this)
}

private fun String.parseCompatSettingsJson(): JsonObject {
    if (isBlank()) return buildJsonObject { }
    return CompatSettingsJson.parseToJsonElement(this).jsonObject
}
