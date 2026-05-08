package me.rerere.rikkahub.ui.pages.setting.locallm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.locallm.LocalRuntime
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Detail page for one Local LLM provider. Shows status, installed-model list,
 * accelerator status with a Re-detect button, manual URL install, and a tool-call
 * info row.
 *
 * The "Manage installed models" section in Phase 22A is intentionally minimal:
 * one model in the list (the one downloaded on first launch). Phase 22B's HF browser
 * + 22C's Model Manager surface are where rich management lives.
 */
@Composable
fun SettingLocalLlmPage(
    runtime: LocalRuntime,
    viewModel: SettingLocalLlmViewModel = koinViewModel(
        key = "detail-${runtime.displayName}",
        parameters = { parametersOf(runtime) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = when (runtime) {
                            LocalRuntime.LiteRT -> stringResource(R.string.local_llm_litert_name)
                            LocalRuntime.LlamaCpp -> stringResource(R.string.local_llm_llamacpp_name)
                        }
                    )
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status / model section
            when (val s = state) {
                is SettingLocalLlmViewModel.UiState.Ready -> {
                    Text(
                        text = "Status: Ready",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Model: ${s.installedModelName}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Accelerator: ${s.accelerator}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { viewModel.reDetectAccelerator() }) {
                            Text(stringResource(R.string.local_llm_re_detect))
                        }
                    }
                    TextButton(
                        onClick = { viewModel.deleteInstalledModel(s.installedModelName) },
                    ) {
                        Text(
                            text = stringResource(R.string.local_llm_delete_model),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                is SettingLocalLlmViewModel.UiState.Idle ->
                    Text(
                        text = stringResource(R.string.local_llm_default_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                is SettingLocalLlmViewModel.UiState.Downloading ->
                    Text(
                        text = "Download in progress: ${s.percent}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                is SettingLocalLlmViewModel.UiState.Error -> {
                    Text(
                        text = stringResource(R.string.local_llm_status_error_format, s.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { viewModel.startDefaultDownload() }) {
                        Text(stringResource(R.string.local_llm_retry))
                    }
                }
            }

            // Manual URL install
            var manualUrl by remember { mutableStateOf("") }
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text(stringResource(R.string.local_llm_install_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    viewModel.startManualDownload(manualUrl)
                    manualUrl = ""
                },
                enabled = manualUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.local_llm_install_url_action))
            }

            // Tool-call info row
            Text(
                text = when (runtime) {
                    LocalRuntime.LiteRT -> stringResource(R.string.local_llm_tool_calling_litert)
                    LocalRuntime.LlamaCpp -> stringResource(R.string.local_llm_tool_calling_llamacpp)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
