package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    allowedKinds: Set<AssistantImportKind> = setOf(
        AssistantImportKind.PRESET,
        AssistantImportKind.CHARACTER_CARD,
    ),
    onImport: (AssistantImportPayload, Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(
            allowedKinds = allowedKinds,
            onImport = onImport,
        )
    }
}

@Composable
private fun SillyTavernImporter(
    allowedKinds: Set<AssistantImportKind>,
    onImport: (AssistantImportPayload, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<AssistantImportPayload?>(null) }
    val importFailedText = stringResource(R.string.assistant_importer_import_failed)
    val characterOnlyText = stringResource(R.string.assistant_importer_character_only)
    val importingText = stringResource(R.string.assistant_importer_importing)
    val importPngText = stringResource(R.string.assistant_importer_import_tavern_png)
    val importJsonOrPresetText = stringResource(R.string.assistant_importer_import_tavern_json_or_preset)
    val importCharacterJsonText = stringResource(R.string.assistant_importer_import_tavern_character_json)
    val missingDataFieldText = stringResource(R.string.assistant_importer_missing_data_field)
    val missingNameFieldText = stringResource(R.string.assistant_importer_missing_name_field)
    val readJsonFailedText = stringResource(R.string.assistant_importer_read_json_failed)

    suspend fun importPayload(payload: AssistantImportPayload, includeRegexes: Boolean) {
        onImport(payload.materializeImportedAvatar(filesManager), includeRegexes)
    }

    fun showImportFailure(exception: Throwable) {
        exception.printStackTrace()
        val message = when (val rawMessage = exception.message) {
            "Missing card data", "Empty character data" -> missingDataFieldText
            "Missing card name" -> missingNameFieldText
            "Failed to read import file" -> readJsonFailedText
            "Unsupported SillyTavern import format" -> resources.getString(
                R.string.assistant_importer_unsupported_spec,
                "SillyTavern import format"
            )
            else -> {
                if (rawMessage?.startsWith("Unsupported file type: ") == true) {
                    resources.getString(
                        R.string.assistant_importer_unsupported_file_type,
                        rawMessage.removePrefix("Unsupported file type: ")
                    )
                } else {
                    rawMessage ?: importFailedText
                }
            }
        }
        toaster.show(message)
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isLoading = true
        scope.launch {
            runCatching {
                parseAssistantImportFromUri(
                    context = context,
                    uri = uri,
                    filesManager = filesManager,
                )
            }.onSuccess { payload ->
                if (payload.kind !in allowedKinds) {
                    toaster.show(characterOnlyText)
                } else if (payload.regexes.isNotEmpty()) {
                    pendingImport = payload
                } else {
                    runCatching {
                        importPayload(payload, false)
                    }.onFailure(::showImportFailure)
                }
            }.onFailure { exception ->
                showImportFailure(exception)
            }
            isLoading = false
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isLoading = true
        scope.launch {
            runCatching {
                parseAssistantImportFromUri(
                    context = context,
                    uri = uri,
                    filesManager = filesManager,
                )
            }.onSuccess { payload ->
                if (payload.kind !in allowedKinds) {
                    toaster.show(characterOnlyText)
                } else if (payload.regexes.isNotEmpty()) {
                    pendingImport = payload
                } else {
                    runCatching {
                        importPayload(payload, false)
                    }.onFailure(::showImportFailure)
                }
            }.onFailure { exception ->
                showImportFailure(exception)
            }
            isLoading = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { pngPickerLauncher.launch(arrayOf("image/png")) },
            enabled = !isLoading,
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(if (isLoading) importingText else importPngText)
        }

        OutlinedButton(
            onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "text/plain")) },
            enabled = !isLoading,
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(
                if (isLoading) {
                    importingText
                } else if (AssistantImportKind.PRESET in allowedKinds) {
                    importJsonOrPresetText
                } else {
                    importCharacterJsonText
                }
            )
        }
    }

    pendingImport?.let { payload ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = {
                Text(stringResource(R.string.assistant_importer_import_regex_title))
            },
            text = {
                Text(
                    when (payload.kind) {
                        AssistantImportKind.PRESET -> stringResource(
                            R.string.assistant_importer_import_regex_preset_message,
                            payload.regexes.size
                        )
                        AssistantImportKind.CHARACTER_CARD -> stringResource(
                            R.string.assistant_importer_import_regex_character_message,
                            payload.regexes.size
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        isLoading = true
                        scope.launch {
                            runCatching {
                                importPayload(payload, true)
                            }.onFailure(::showImportFailure)
                            isLoading = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_importer_import_all))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pendingImport = null
                            isLoading = true
                            scope.launch {
                                runCatching {
                                    importPayload(payload, false)
                                }.onFailure(::showImportFailure)
                                isLoading = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.assistant_importer_skip_regex))
                    }
                    TextButton(onClick = { pendingImport = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}
