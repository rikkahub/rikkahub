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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.rerere.rikkahub.ui.components.ui.FormItem

val MediaGenerationProviderSetting.typeName: String
    get() = when (this) {
        is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
        is MediaGenerationProviderSetting.Aliyun -> "阿里云百炼"
        is MediaGenerationProviderSetting.Volcengine -> "火山方舟"
        is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
    }

val MediaKind.label: String
    get() = when (this) {
        MediaKind.IMAGE -> "图像"
        MediaKind.VIDEO -> "视频"
    }

@Composable
fun MediaGenerationProviderConfigure(
    setting: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        FormItem(label = { Text("提供商类型") }) {
            OutlinedTextField(
                value = setting.typeName,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormItem(label = { Text("名称") }) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        FormItem(label = { Text("API Key") }) {
            OutlinedTextField(
                value = setting.apiKey,
                onValueChange = { onValueChange(setting.copyProvider(apiKey = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        FormItem(label = { Text("Base URL") }) {
            OutlinedTextField(
                value = setting.baseUrl,
                onValueChange = { onValueChange(setting.copyProvider(baseUrl = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        if (setting is MediaGenerationProviderSetting.Aliyun) {
            FormItem(
                label = { Text("业务空间 ID") },
                description = {
                    Text("替换 Base URL 里的 ${MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER}，地址不含占位符时可以留空")
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

        FormItem(
            label = { Text("模型") },
            description = { Text("模型 ID 会原样下发给接口") }
        ) {
            MediaGenerationModelList(
                models = setting.models,
                supportedKinds = setting.supportedKinds,
                onValueChange = { onValueChange(setting.copyProvider(models = it)) }
            )
        }
    }
}

@Composable
private fun MediaGenerationModelList(
    models: List<MediaGenerationModel>,
    supportedKinds: Set<MediaKind>,
    onValueChange: (List<MediaGenerationModel>) -> Unit
) {
    // 按枚举顺序排列，保证分段按钮的顺序稳定
    val kinds = MediaKind.entries.filter { it in supportedKinds }

    fun update(model: MediaGenerationModel) {
        onValueChange(models.map { if (it.id == model.id) model else it })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { model ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = model.modelId,
                        onValueChange = { update(model.copy(modelId = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("模型 ID") },
                        singleLine = true
                    )
                    // 只支持一种类型的厂商不需要选择
                    if (kinds.size > 1) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            kinds.forEachIndexed { index, kind ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                                    selected = model.kind == kind,
                                    onClick = { update(model.copy(kind = kind)) },
                                ) {
                                    Text(kind.label)
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { onValueChange(models.filter { it.id != model.id }) }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                }
            }
        }

        TextButton(
            onClick = { onValueChange(models + MediaGenerationModel(modelId = "", kind = kinds.first())) },
            enabled = kinds.isNotEmpty()
        ) {
            Icon(HugeIcons.Add01, null)
            Text("添加模型")
        }
    }
}
