package me.rerere.rikkahub.ui.pages.quickstart

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DEEPSEEK_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.openUrl

private const val DEEPSEEK_WEB_URL = "https://chat.deepseek.com"
private const val DEEPSEEK_API_KEYS_URL = "https://platform.deepseek.com/api_keys"

@Composable
internal fun QuickStartDeepSeekTab(vm: QuickStartVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val importState by vm.deepSeekImport.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current

    val assistantName = settings.getCurrentAssistant().name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    val deepSeekProvider = settings.providers.find { it.id == DEEPSEEK_PROVIDER_ID } as? ProviderSetting.ApiKey
    val deepSeekReady = deepSeekProvider != null && deepSeekProvider.enabled &&
        deepSeekProvider.apiKey.isNotBlank() && deepSeekProvider.models.isNotEmpty()
    val imported = importState is UiState.Success

    val pickZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importDeepSeek)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuickStartHeroCard(
            title = stringResource(R.string.quick_start_page_deepseek_title),
            description = stringResource(R.string.quick_start_page_deepseek_desc),
            icon = {
                AutoAIIcon(
                    name = "deepseek",
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.surface,
                )
            },
        )

        CardGroup(title = { Text(stringResource(R.string.quick_start_page_deepseek_migrate_chats)) }) {
            stepItem(
                index = 1,
                title = R.string.quick_start_page_deepseek_export_title,
                description = R.string.quick_start_page_deepseek_export_desc,
                state = if (imported) StepState.DONE else StepState.TODO,
            ) {
                FilledTonalButton(
                    onClick = { context.openUrl(DEEPSEEK_WEB_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Link01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.quick_start_page_deepseek_open_web), modifier = Modifier.padding(start = 8.dp))
                }
            }

            stepItem(
                index = 2,
                title = R.string.quick_start_page_deepseek_import_title,
                description = R.string.quick_start_page_deepseek_import_desc,
                descriptionArgs = listOf(assistantName),
                state = if (imported) StepState.DONE else StepState.TODO,
            ) {
                when (val state = importState) {
                    is UiState.Success -> Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_summary,
                            state.data.importedConversations,
                            state.data.skippedExistingConversations,
                            state.data.skippedEmptyConversations,
                            state.data.missingAttachments,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is UiState.Error -> Text(
                        text = stringResource(R.string.backup_page_restore_failed, state.error.message ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }
                val loading = importState is UiState.Loading
                Button(
                    onClick = { pickZipLauncher.launch(arrayOf("application/zip")) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.quick_start_page_deepseek_importing), modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(
                                if (imported) R.string.quick_start_page_deepseek_import_another else R.string.quick_start_page_deepseek_pick_zip
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.quick_start_page_deepseek_keep_using)) }) {
            stepItem(
                index = 3,
                title = R.string.quick_start_page_deepseek_api_title,
                description = R.string.quick_start_page_deepseek_api_desc,
                state = if (deepSeekReady) StepState.DONE else StepState.OPTIONAL,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { context.openUrl(DEEPSEEK_API_KEYS_URL) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(HugeIcons.Key01, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.quick_start_page_deepseek_get_key), modifier = Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = {
                            navController.navigate(
                                if (deepSeekProvider != null) {
                                    Screen.SettingProviderDetail(DEEPSEEK_PROVIDER_ID.toString())
                                } else {
                                    Screen.SettingProvider
                                }
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.quick_start_page_deepseek_configure))
                    }
                }
            }
        }
    }
}
