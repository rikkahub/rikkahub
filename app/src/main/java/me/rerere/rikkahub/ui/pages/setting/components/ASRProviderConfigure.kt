package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.ui.components.OutlinedNumberInput

val ASRProviderSetting.typeName: String
    get() = when (this) {
        is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
        is ASRProviderSetting.DashScope -> "DashScope"
        is ASRProviderSetting.Volcengine -> "Volcengine"
        is ASRProviderSetting.MiMo -> "MiMo"
        is ASRProviderSetting.Step -> "Step"
    }

@Composable
fun ASRProviderConfigure(
    setting: ASRProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        CardGroup {
            // 类型在新建时选定，之后不能改
            item(
                headlineContent = { Text(stringResource(R.string.setting_asr_configure_provider_type)) },
                supportingContent = { Text(stringResource(R.string.setting_asr_configure_provider_type_desc)) },
                trailingContent = { CardGroupValue(setting.typeName) },
            )

            formItem(
                headlineContent = { Text(stringResource(R.string.setting_asr_configure_name)) },
                supportingContent = { Text(stringResource(R.string.setting_asr_configure_name_desc)) }
            ) {
                OutlinedTextField(
                    value = setting.name,
                    onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("OpenAI Realtime") }
                )
            }
        }

        CardGroup {
            when (setting) {
                is ASRProviderSetting.OpenAIRealtime -> openAIRealtimeASRItems(setting, onValueChange)
                is ASRProviderSetting.DashScope -> dashScopeASRItems(setting, onValueChange)
                is ASRProviderSetting.Volcengine -> volcengineASRItems(setting, onValueChange)
                is ASRProviderSetting.MiMo -> miMoASRItems(setting, onValueChange)
                is ASRProviderSetting.Step -> stepASRItems(setting, onValueChange)
            }
        }
    }
}

private fun CardGroupScope.openAIRealtimeASRItems(
    setting: ASRProviderSetting.OpenAIRealtime,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_openai_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_openai_websocket_desc)) }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://api.openai.com/v1/realtime?intent=transcription") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_model)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gpt-4o-transcribe") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_language)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_language_iso_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_prompt)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_prompt_desc)) }
    ) {
        OutlinedTextField(
            value = setting.prompt,
            onValueChange = { onValueChange(setting.copy(prompt = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text("Optional") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_vad_threshold)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_vad_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.vadThreshold,
            onValueChange = { value ->
                if (value in 0.0f..1.0f) {
                    onValueChange(setting.copy(vadThreshold = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "VAD Threshold"
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_prefix_padding)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_prefix_padding_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.prefixPaddingMs,
            onValueChange = { value ->
                if (value in 0..2000) {
                    onValueChange(setting.copy(prefixPaddingMs = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Prefix Padding"
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_silence_duration)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_silence_duration_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.silenceDurationMs,
            onValueChange = { value ->
                if (value in 100..5000) {
                    onValueChange(setting.copy(silenceDurationMs = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Silence Duration"
        )
    }
}

private fun CardGroupScope.dashScopeASRItems(
    setting: ASRProviderSetting.DashScope,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_dashscope_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_dashscope_websocket_desc)) }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://dashscope.aliyuncs.com/api-ws/v1/realtime") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_model)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-asr-flash-realtime-2026-02-10") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_language)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_language_iso_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("zh") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_vad_threshold)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_dashscope_vad_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.vadThreshold,
            onValueChange = { value ->
                if (value in -1.0f..1.0f) {
                    onValueChange(setting.copy(vadThreshold = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "VAD Threshold"
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_silence_duration)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_silence_duration_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.silenceDurationMs,
            onValueChange = { value ->
                if (value in 200..6000) {
                    onValueChange(setting.copy(silenceDurationMs = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Silence Duration"
        )
    }
}

private fun CardGroupScope.volcengineASRItems(
    setting: ASRProviderSetting.Volcengine,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_volcengine_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-api-key") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_volcengine_websocket_desc)) }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_resource_id)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_resource_id_desc)) }
    ) {
        OutlinedTextField(
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("volc.seedasr.sauc.duration") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_language)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_language_code_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_page_asr_silence_duration)) },
        supportingContent = { Text(stringResource(R.string.setting_page_asr_silence_duration_desc)) },
    ) {
        OutlinedNumberInput(
            value = setting.silenceDurationMs,
            onValueChange = { value ->
                if (value in 300..5000) onValueChange(setting.copy(silenceDurationMs = value))
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_page_asr_milliseconds),
        )
    }

}

private fun CardGroupScope.miMoASRItems(
    setting: ASRProviderSetting.MiMo,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_mimo_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-... or tp-...") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_mimo_base_url_desc)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_model)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_mimo_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2.5-asr") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_language)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_mimo_language_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_sample_rate)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_mimo_sample_rate_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.sampleRate,
            onValueChange = { value ->
                if (value in 8000..48000) {
                    onValueChange(setting.copy(sampleRate = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Sample Rate"
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_segment_duration)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_mimo_segment_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.segmentDurationSec,
            onValueChange = { value ->
                if (value in 0..300) {
                    onValueChange(setting.copy(segmentDurationSec = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Segment Duration (s)"
        )
    }
}

private fun CardGroupScope.stepASRItems(
    setting: ASRProviderSetting.Step,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-stepfun-api-key") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_base_url_desc)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_model)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("stepaudio-2.5-asr") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_language)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_language_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_sample_rate)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_sample_rate_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.sampleRate,
            onValueChange = { value ->
                if (value in 8000..48000) {
                    onValueChange(setting.copy(sampleRate = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Sample Rate"
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_segment_duration)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_segment_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.segmentDurationSec,
            onValueChange = { value ->
                if (value in 0..300) {
                    onValueChange(setting.copy(segmentDurationSec = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Segment Duration (s)"
        )
    }

    switchItem(
        checked = setting.enableItn,
        onCheckedChange = { onValueChange(setting.copy(enableItn = it)) },
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_step_itn)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_itn_desc)) },
    )

    switchItem(
        checked = setting.enableTimestamp,
        onCheckedChange = { onValueChange(setting.copy(enableTimestamp = it)) },
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_step_timestamp)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_timestamp_desc)) },
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_asr_configure_step_hotwords)) },
        supportingContent = { Text(stringResource(R.string.setting_asr_configure_step_hotwords_desc)) }
    ) {
        OutlinedTextField(
            // 用逗号分隔展示, 输入时按逗号 split 回 List
            value = setting.hotwords.joinToString(","),
            onValueChange = { text ->
                val list = text.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                onValueChange(setting.copy(hotwords = list))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("热词1, 热词2, 热词3") }
        )
    }
}
