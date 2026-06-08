package me.rerere.rikkahub.ui.pages.setting.providerdetail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.ui.SiliconFlowPowerByIcon
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConnectionTester
import me.rerere.rikkahub.ui.pages.setting.components.SettingProviderBalanceOption
import me.rerere.rikkahub.ui.pages.setting.components.isUsingDefaultBaseUrl
import me.rerere.rikkahub.ui.pages.setting.components.resetBaseUrlToDefault

@Composable
internal fun SettingProviderConfigPage(
    provider: ProviderSetting,
    persisted: ProviderSetting,
    onDraftChange: (ProviderSetting) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProviderConfigure(
            provider = provider,
            onEdit = {
                onDraftChange(it)
            }
        )

        if (provider is ProviderSetting.OpenAI) {
            SettingProviderBalanceOption(
                provider = provider,
                balanceOption = provider.balanceOption,
                onEdit = { onDraftChange(provider.copyProvider(balanceOption = it)) }
            )
            ProviderBalanceText(providerSetting = provider, style = MaterialTheme.typography.labelSmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Feed the tester the draft's config (apiKey/baseUrl under test) with the LIVE
            // persisted model list, so a model added in the Models tab shows in the tester's
            // picker immediately, without exit+re-enter re-seeding the draft (issue #208).
            ProviderConnectionTester(
                internalProvider = mergeConfigKeepingModels(draft = provider, persisted = persisted),
            )

            Spacer(Modifier.weight(1f))

            if (!provider.builtIn) {
                IconButton(
                    onClick = {
                        showDeleteDialog = true
                    },
                ) {
                    Icon(HugeIcons.Delete01, null)
                }
            }

            IconButton(
                onClick = {
                    onDraftChange(provider.resetBaseUrlToDefault())
                },
                enabled = !provider.isUsingDefaultBaseUrl(),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh03,
                    contentDescription = stringResource(R.string.setting_model_page_reset_to_default)
                )
            }

            Button(
                onClick = {
                    onSave()
                }
            ) {
                Text(stringResource(R.string.setting_provider_page_save))
            }
        }

        // 硅基流动图标
        if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
            SiliconFlowPowerByIcon(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp)
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(stringResource(R.string.confirm_delete))
            },
            text = {
                Text(stringResource(R.string.setting_provider_page_delete_dialog_text))
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        )
    }
}
