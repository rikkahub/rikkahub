package me.rerere.rikkahub.ui.pages.setting.locallm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * Tile that renders a Local-LLM provider in the existing Providers list.
 *
 * Mirrors ProviderItem's full-height Card layout (avatar + title + shortDescription + tags)
 * but replaces the drag handle with an inline Switch, per explicit user request.
 *
 * State is driven off [SettingLocalLlmViewModel.state]:
 *  - Idle        -> "Disabled" tag + description; toggle ON triggers download dialog
 *  - Downloading -> progress bar + % tag; toggle is disabled
 *  - Ready       -> "Enabled" tag + model count; toggle flips ProviderSetting.enabled
 *  - Error       -> "Download error" tag; detail page hosts the Retry button
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalLlmProviderTile(
    provider: ProviderSetting,
    viewModel: SettingLocalLlmViewModel,
    onTapDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    val isDownloading = state is SettingLocalLlmViewModel.UiState.Downloading
    val isReady = state is SettingLocalLlmViewModel.UiState.Ready

    Card(
        modifier = modifier.combinedClickable(
            onClick = onTapDetail,
            onLongClick = { /* no-op for v1 */ },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled) {
                CustomColors.listItemColors.containerColor
            } else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top row: avatar + inline toggle
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = provider.name,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = provider.enabled,
                    enabled = !isDownloading,
                    onCheckedChange = { newValue ->
                        when {
                            newValue && state is SettingLocalLlmViewModel.UiState.Idle ->
                                showDialog = true
                            newValue && isReady ->
                                viewModel.setProviderEnabled(true)
                            !newValue && provider.enabled ->
                                viewModel.setProviderEnabled(false)
                        }
                    },
                )
            }

            // Title + shortDescription + tags
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(
                        LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)
                    ) {
                        provider.shortDescription()
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(
                            stringResource(
                                if (provider.enabled) R.string.setting_provider_page_enabled
                                else R.string.setting_provider_page_disabled
                            )
                        )
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                    when (val s = state) {
                        is SettingLocalLlmViewModel.UiState.Downloading -> {
                            Tag(type = TagType.INFO) {
                                Text("Downloading ${s.percent}%")
                            }
                        }
                        is SettingLocalLlmViewModel.UiState.Error -> {
                            Tag(type = TagType.WARNING) {
                                Text("Download error")
                            }
                        }
                        else -> { /* no extra tag */ }
                    }
                }

                if (isDownloading) {
                    val s = state as SettingLocalLlmViewModel.UiState.Downloading
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (showDialog) {
        LocalLlmDownloadDialog(
            title = stringResource(R.string.local_llm_download_title),
            message = stringResource(R.string.local_llm_download_message),
            onConfirm = {
                showDialog = false
                viewModel.startDefaultDownload()
            },
            onDismiss = { showDialog = false },
        )
    }
}

internal fun humanBytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.1f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000L -> "%d MB".format(b / 1_000_000L)
    b >= 1_000L -> "%d KB".format(b / 1_000L)
    else -> "$b B"
}
