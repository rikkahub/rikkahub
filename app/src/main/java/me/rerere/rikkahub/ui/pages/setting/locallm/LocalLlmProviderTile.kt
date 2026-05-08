package me.rerere.rikkahub.ui.pages.setting.locallm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R

/**
 * Tile that renders a Local-LLM provider in the existing Providers list. State-driven
 * off [SettingLocalLlmViewModel.state]:
 *  - Idle  -> caption explains what's needed; toggle ON triggers the download dialog
 *  - Downloading -> progress underneath the headline
 *  - Ready  -> "<model name> · <accelerator>" caption; tap routes into the detail page
 *  - Error  -> error caption; the detail page is where Retry lives
 */
@Composable
fun LocalLlmProviderTile(
    headline: String,
    viewModel: SettingLocalLlmViewModel,
    onTapDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                val supporting = when (val s = state) {
                    is SettingLocalLlmViewModel.UiState.Idle ->
                        stringResource(R.string.local_llm_default_disabled)
                    is SettingLocalLlmViewModel.UiState.Downloading -> {
                        val read = humanBytes(s.bytesRead)
                        val total = s.totalBytes?.let { humanBytes(it) } ?: "?"
                        stringResource(R.string.local_llm_downloading_format, s.percent, read, total)
                    }
                    is SettingLocalLlmViewModel.UiState.Ready ->
                        stringResource(R.string.local_llm_status_ready_format, s.installedModelName, s.accelerator)
                    is SettingLocalLlmViewModel.UiState.Error ->
                        stringResource(R.string.local_llm_status_error_format, s.message)
                }
                Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
            val isEnabled = state is SettingLocalLlmViewModel.UiState.Ready
            Switch(
                checked = isEnabled,
                onCheckedChange = { newValue ->
                    if (newValue && state is SettingLocalLlmViewModel.UiState.Idle) {
                        showDialog = true
                    }
                },
            )
        }

        if (state is SettingLocalLlmViewModel.UiState.Downloading) {
            val s = state as SettingLocalLlmViewModel.UiState.Downloading
            LinearProgressIndicator(
                progress = { s.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
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

private fun humanBytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.1f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000L -> "%d MB".format(b / 1_000_000L)
    b >= 1_000L -> "%d KB".format(b / 1_000L)
    else -> "$b B"
}
