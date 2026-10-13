package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel

val MediaGenerationProviderSetting.typeName: String
    @Composable get() = when (this) {
        is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
        is MediaGenerationProviderSetting.Aliyun -> stringResource(R.string.media_provider_type_aliyun)
        is MediaGenerationProviderSetting.Volcengine -> stringResource(R.string.media_provider_type_volcengine)
        is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
        is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
    }

val MediaKind.label: String
    @Composable get() = when (this) {
        MediaKind.IMAGE -> stringResource(R.string.media_kind_image)
        MediaKind.VIDEO -> stringResource(R.string.video)
    }

@Composable
fun MediaGenerationProviderConfigure(
    setting: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit
) {
    val models = setting.models
    // 按枚举顺序排列，保证按钮组的顺序稳定
    val kinds = MediaKind.entries.filter { it in setting.supportedKinds }

    fun updateModels(models: List<MediaGenerationModel>) {
        onValueChange(setting.copyProvider(models = models))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        CardGroup {
            // 类型在新建时选定，之后不能改
            item(
                headlineContent = { Text(stringResource(R.string.setting_media_page_provider_type)) },
                trailingContent = { CardGroupValue(setting.typeName) },
            )

            formItem(
                headlineContent = { Text(stringResource(R.string.setting_media_page_provider_name)) },
            ) {
                OutlinedTextField(
                    value = setting.name,
                    onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            formItem(headlineContent = { Text("API Key") }) {
                OutlinedTextField(
                    value = setting.apiKey,
                    onValueChange = { onValueChange(setting.copyProvider(apiKey = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            formItem(headlineContent = { Text("Base URL") }) {
                OutlinedTextField(
                    value = setting.baseUrl,
                    onValueChange = { onValueChange(setting.copyProvider(baseUrl = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            if (setting is MediaGenerationProviderSetting.Aliyun) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_media_page_workspace_id)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.setting_media_page_workspace_id_desc,
                                MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER,
                            )
                        )
                    }
                ) {
                    OutlinedTextField(
                        value = setting.workspaceId,
                        onValueChange = { onValueChange(setting.copy(workspaceId = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        CardGroup(
            title = { Text(stringResource(R.string.setting_media_page_models)) },
        ) {
            models.forEach { model ->
                formItem {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = model.modelId,
                            onValueChange = { value ->
                                updateModels(models.map { if (it.id == model.id) model.copy(modelId = value.trim()) else it })
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.setting_media_page_model_id)) },
                            singleLine = true
                        )
                        IconButton(onClick = { updateModels(models.filter { it.id != model.id }) }) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                        }
                    }
                    // 只支持一种类型的厂商不需要选择
                    if (kinds.size > 1) {
                        ConnectedChoiceButtons(
                            options = kinds,
                            selected = model.kind,
                            onSelect = { kind ->
                                updateModels(models.map { if (it.id == model.id) model.copy(kind = kind) else it })
                            },
                        ) { kind, selected ->
                            ConnectedChoiceLabel(text = kind.label, selected = selected)
                        }
                    }
                }
            }

            item(
                onClick = if (kinds.isNotEmpty()) {
                    { updateModels(models + MediaGenerationModel(modelId = "", kind = kinds.first())) }
                } else null,
                headlineContent = { Text(stringResource(R.string.setting_media_page_add_model)) },
                supportingContent = { Text(stringResource(R.string.setting_media_page_models_desc)) },
                leadingContent = { CardGroupIcon(HugeIcons.Add01) },
            )
        }
    }
}
