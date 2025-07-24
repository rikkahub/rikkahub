package me.rerere.rikkahub.ui.pages.setting.components

package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.setting.TempApiConfig

@Composable
fun ProviderConfigure(
    providerType: ProviderType,
    tempApiConfig: TempApiConfig,
    modifier: Modifier = Modifier,
    onEdit: (tempApiConfig: TempApiConfig) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        when (providerType) {
            ProviderType.OpenAI -> {
                ProviderConfigureOpenAI(tempApiConfig, onEdit)
            }

            ProviderType.Google -> {
                ProviderConfigureGoogle(tempApiConfig, onEdit)
            }

            ProviderType.Claude -> {
                ProviderConfigureClaude(tempApiConfig, onEdit)
            }
        }
    }
}

@Composable
private fun ColumnScope.ProviderConfigureOpenAI(
    config: TempApiConfig,
    onEdit: (config: TempApiConfig) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = config.enabled,
            onCheckedChange = {
                onEdit(config.copy(enabled = it))
            }
        )
    }

    OutlinedTextField(
        value = config.name,
        onValueChange = {
            onEdit(config.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = config.apiKey,
        onValueChange = {
            onEdit(config.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    OutlinedTextField(
        value = config.baseUrl,
        onValueChange = {
            onEdit(config.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_response_api), modifier = Modifier.weight(1f))
        Checkbox(
            checked = config.useResponseApi,
            onCheckedChange = {
                onEdit(config.copy(useResponseApi = it))
            }
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureClaude(
    config: TempApiConfig,
    onEdit: (config: TempApiConfig) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = config.enabled,
            onCheckedChange = {
                onEdit(config.copy(enabled = it))
            }
        )
    }

    OutlinedTextField(
        value = config.name,
        onValueChange = {
            onEdit(config.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    OutlinedTextField(
        value = config.apiKey,
        onValueChange = {
            onEdit(config.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = config.baseUrl,
        onValueChange = {
            onEdit(config.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ColumnScope.ProviderConfigureGoogle(
    config: TempApiConfig,
    onEdit: (config: TempApiConfig) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = config.enabled,
            onCheckedChange = {
                onEdit(config.copy(enabled = it))
            }
        )
    }

    OutlinedTextField(
        value = config.name,
        onValueChange = {
            onEdit(config.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = config.apiKey,
        onValueChange = {
            onEdit(config.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    if (!config.vertexAI) {
        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = {
                onEdit(config.copy(baseUrl = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_vertex_ai), modifier = Modifier.weight(1f))
        Checkbox(
            checked = config.vertexAI,
            onCheckedChange = {
                onEdit(config.copy(vertexAI = it))
            }
        )
    }

    if (config.vertexAI) {
        OutlinedTextField(
            value = config.location,
            onValueChange = {
                onEdit(config.copy(location = it.trim()))
            },
            label = {
                // https://cloud.google.com/vertex-ai/generative-ai/docs/learn/locations#available-regions
                Text(stringResource(id = R.string.setting_provider_page_location))
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = config.projectId,
            onValueChange = {
                onEdit(config.copy(projectId = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_project_id))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
