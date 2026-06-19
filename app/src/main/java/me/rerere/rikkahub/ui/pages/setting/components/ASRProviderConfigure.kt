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
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput

@Composable
fun ASRProviderConfigure(
    setting: ASRProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_provider_type)) },
            description = { Text(stringResource(R.string.setting_asr_configure_provider_type_desc)) }
        ) {
            OutlinedTextField(
                value = when (setting) {
                    is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
                    is ASRProviderSetting.DashScope -> "DashScope"
                    is ASRProviderSetting.Volcengine -> "Volcengine"
                    is ASRProviderSetting.MiMo -> "MiMo"
                    is ASRProviderSetting.Step -> "Step"
                },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_name)) },
            description = { Text(stringResource(R.string.setting_asr_configure_name_desc)) }
        ) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("OpenAI Realtime") }
            )
        }

        when (setting) {
            is ASRProviderSetting.OpenAIRealtime -> OpenAIRealtimeASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.DashScope -> DashScopeASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.Volcengine -> VolcengineASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.MiMo -> MiMoASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.Step -> StepASRConfiguration(setting, onValueChange)
        }
    }
}

@Composable
private fun OpenAIRealtimeASRConfiguration(
    setting: ASRProviderSetting.OpenAIRealtime,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_openai_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_openai_websocket_desc)) }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://api.openai.com/v1/realtime?intent=transcription") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_model)) },
        description = { Text(stringResource(R.string.setting_asr_configure_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gpt-4o-transcribe") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_language_iso_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_prompt)) },
        description = { Text(stringResource(R.string.setting_asr_configure_prompt_desc)) }
    ) {
        OutlinedTextField(
            value = setting.prompt,
            onValueChange = { onValueChange(setting.copy(prompt = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text("Optional") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_vad_threshold)) },
        description = { Text(stringResource(R.string.setting_asr_configure_vad_desc)) }
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

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_prefix_padding)) },
        description = { Text(stringResource(R.string.setting_asr_configure_prefix_padding_desc)) }
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

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_silence_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_silence_duration_desc)) }
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

@Composable
private fun DashScopeASRConfiguration(
    setting: ASRProviderSetting.DashScope,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_dashscope_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_dashscope_websocket_desc)) }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://dashscope.aliyuncs.com/api-ws/v1/realtime") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_model)) },
        description = { Text(stringResource(R.string.setting_asr_configure_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-asr-flash-realtime-2026-02-10") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_language_iso_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("zh") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_vad_threshold)) },
        description = { Text(stringResource(R.string.setting_asr_configure_dashscope_vad_desc)) }
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

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_silence_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_silence_duration_desc)) }
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

@Composable
private fun VolcengineASRConfiguration(
    setting: ASRProviderSetting.Volcengine,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_volcengine_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-api-key") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_volcengine_websocket_desc)) }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_resource_id)) },
        description = { Text(stringResource(R.string.setting_asr_configure_resource_id_desc)) }
    ) {
        OutlinedTextField(
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("volc.bigasr.sauc.duration") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_language_code_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }
}

@Composable
private fun MiMoASRConfiguration(
    setting: ASRProviderSetting.MiMo,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_mimo_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-... or tp-...") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_base_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_mimo_base_url_desc)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_model)) },
        description = { Text(stringResource(R.string.setting_asr_configure_mimo_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2.5-asr") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_mimo_language_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_sample_rate)) },
        description = { Text(stringResource(R.string.setting_asr_configure_mimo_sample_rate_desc)) }
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

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_segment_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_mimo_segment_desc)) }
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

@Composable
private fun StepASRConfiguration(
    setting: ASRProviderSetting.Step,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-stepfun-api-key") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_base_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_base_url_desc)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_model)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_model_desc)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("stepaudio-2.5-asr") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_language_desc)) }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_sample_rate)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_sample_rate_desc)) }
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

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_segment_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_segment_desc)) }
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

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_step_itn)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_itn_desc)) }
    ) {
        androidx.compose.material3.Switch(
            checked = setting.enableItn,
            onCheckedChange = { onValueChange(setting.copy(enableItn = it)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_step_timestamp)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_timestamp_desc)) }
    ) {
        androidx.compose.material3.Switch(
            checked = setting.enableTimestamp,
            onCheckedChange = { onValueChange(setting.copy(enableTimestamp = it)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_step_hotwords)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_hotwords_desc)) }
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
