package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.DEFAULT_TEXT_SELECTION_ACTIONS
import me.rerere.rikkahub.data.model.TextSelectionAction
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.icons.Lucide
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private val CommonLanguages = listOf(
    "English",
    "Chinese",
    "Japanese",
    "Korean",
    "French",
    "German",
    "Spanish",
    "Russian",
    "Portuguese",
    "Italian",
)

@Composable
fun SettingAndroidIntegrationPage(
    settingsStore: SettingsStore = koinInject(),
) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val config = settings.textSelectionConfig
    var editingAction by remember { mutableStateOf<TextSelectionAction?>(null) }
    val assistantOptions = remember(settings.assistants) { settings.assistants.map { it.id } }
    val selectedAssistant = config.assistantId?.takeIf { it in assistantOptions }
        ?: settings.assistantId.takeIf { it in assistantOptions }
        ?: assistantOptions.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_android_integration)) },
                navigationIcon = { BackButton() },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("tryIt") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.text_selection_try_it)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.text_selection_setup_instructions)) },
                        supportingContent = {
                            SelectionContainer {
                                Text(stringResource(R.string.text_selection_demo_text))
                            }
                        },
                    )
                }
            }

            item("basic") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.settings)) },
                ) {
                    if (selectedAssistant != null) {
                        item(
                            headlineContent = { Text(stringResource(R.string.text_selection_assistant)) },
                            supportingContent = {
                                Text(
                                    settings.assistants.find { it.id == config.assistantId }?.name
                                        ?: stringResource(R.string.text_selection_use_default)
                                )
                            },
                            trailingContent = {
                                Select(
                                    options = assistantOptions,
                                    selectedOption = selectedAssistant,
                                    onOptionSelected = { id ->
                                        scope.launch {
                                            settingsStore.update {
                                                it.copy(textSelectionConfig = config.copy(assistantId = id))
                                            }
                                        }
                                    },
                                    optionToString = { id ->
                                        settings.assistants.find { it.id == id }?.name.orEmpty()
                                    },
                                    modifier = Modifier.width(150.dp),
                                )
                            }
                        )
                    }

                    item(
                        onClick = {
                            scope.launch {
                                settingsStore.update {
                                    it.copy(textSelectionConfig = config.copy(assistantId = null))
                                }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.text_selection_use_default)) },
                    )

                    item(
                        headlineContent = { Text(stringResource(R.string.text_selection_translate_language)) },
                        supportingContent = { Text(config.translateLanguage) },
                        trailingContent = {
                            Select(
                                options = CommonLanguages,
                                selectedOption = config.translateLanguage,
                                onOptionSelected = { language ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(textSelectionConfig = config.copy(translateLanguage = language))
                                        }
                                    }
                                },
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    )

                    item(
                        onClick = {
                            scope.launch {
                                settingsStore.update {
                                    it.copy(
                                        textSelectionConfig = config.copy(actions = DEFAULT_TEXT_SELECTION_ACTIONS)
                                    )
                                }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.reset)) },
                        supportingContent = { Text(stringResource(R.string.text_selection_reset_actions_desc)) },
                    )
                }
            }

            item("actions") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.text_selection_actions)) },
                ) {
                    config.actions.forEach { action ->
                        item(
                            onClick = { editingAction = action },
                            headlineContent = { Text(action.name) },
                            supportingContent = {
                                Text(
                                    text = action.prompt.replace("\n", " "),
                                    maxLines = 1,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = iconForAction(action.icon),
                                    contentDescription = null
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = action.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            settingsStore.update {
                                                it.copy(
                                                    textSelectionConfig = config.copy(
                                                        actions = config.actions.map { current ->
                                                            if (current.id == action.id) {
                                                                current.copy(enabled = enabled)
                                                            } else {
                                                                current
                                                            }
                                                        }
                                                    )
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    editingAction?.let { action ->
        EditActionDialog(
            action = action,
            onDismiss = { editingAction = null },
            onSave = { updated ->
                scope.launch {
                    settingsStore.update {
                        it.copy(
                            textSelectionConfig = config.copy(
                                actions = config.actions.map { current ->
                                    if (current.id == updated.id) updated else current
                                }
                            )
                        )
                    }
                }
                editingAction = null
            }
        )
    }
}

@Composable
private fun EditActionDialog(
    action: TextSelectionAction,
    onDismiss: () -> Unit,
    onSave: (TextSelectionAction) -> Unit,
) {
    var name by remember(action.id) { mutableStateOf(action.name) }
    var prompt by remember(action.id) { mutableStateOf(action.prompt) }
    var enabled by remember(action.id) { mutableStateOf(action.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_selection_edit_action)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.text_selection_action_name)) },
                    enabled = !action.isCustomPrompt,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.text_selection_action_prompt)) },
                    minLines = 4,
                )
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                    Text(stringResource(R.string.enabled))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        action.copy(
                            name = name.trim().ifBlank { action.name },
                            prompt = prompt.trim().ifBlank { action.prompt },
                            enabled = enabled,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun iconForAction(icon: String) = when (icon.lowercase()) {
    "translate" -> Lucide.Languages
    "lightbulb" -> Lucide.Lightbulb
    "summarize" -> Lucide.BookOpenText
    "ask" -> Lucide.Sparkles
    else -> Lucide.Sparkles
}
