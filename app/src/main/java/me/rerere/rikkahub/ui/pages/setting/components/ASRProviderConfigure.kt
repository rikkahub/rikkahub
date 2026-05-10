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
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRProviderSetting
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
            label = { Text("Provider Type") },
            description = { Text("Realtime speech recognition provider") }
        ) {
            OutlinedTextField(
                value = when (setting) {
                    is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
                    is ASRProviderSetting.DashScope -> "DashScope"
                    is ASRProviderSetting.Volcengine -> "Volcengine"
                },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormItem(
            label = { Text("Name") },
            description = { Text("Custom name for this ASR provider") }
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
        }
    }
}

@Composable
private fun OpenAIRealtimeASRConfiguration(
    setting: ASRProviderSetting.OpenAIRealtime,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text("API Key") },
        description = { Text("OpenAI API key used by the realtime websocket") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    FormItem(
        label = { Text("WebSocket URL") },
        description = { Text("OpenAI realtime websocket endpoint") }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://api.openai.com/v1/realtime?intent=transcription") }
        )
    }

    FormItem(
        label = { Text("Model") },
        description = { Text("Realtime transcription model") }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gpt-4o-transcribe") }
        )
    }

    FormItem(
        label = { Text("Language") },
        description = { Text("Optional ISO language code, such as zh or en") }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    FormItem(
        label = { Text("Prompt") },
        description = { Text("Optional hint for names, terms, or formatting") }
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
        label = { Text("VAD Threshold") },
        description = { Text("Server VAD sensitivity, usually 0.3 - 0.8") }
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
        label = { Text("Prefix Padding") },
        description = { Text("Audio kept before detected speech, in milliseconds") }
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
        label = { Text("Silence Duration") },
        description = { Text("Silence duration that ends a turn, in milliseconds") }
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
        label = { Text("API Key") },
        description = { Text("DashScope API key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    FormItem(
        label = { Text("WebSocket URL") },
        description = { Text("DashScope realtime websocket endpoint") }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://dashscope.aliyuncs.com/api-ws/v1/realtime") }
        )
    }

    FormItem(
        label = { Text("Model") },
        description = { Text("Realtime transcription model") }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-asr-flash-realtime-2026-02-10") }
        )
    }

    FormItem(
        label = { Text("Language") },
        description = { Text("Optional ISO language code, such as zh or en") }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("zh") }
        )
    }

    FormItem(
        label = { Text("VAD Threshold") },
        description = { Text("Server VAD sensitivity, set 0 to disable VAD") }
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
        label = { Text("Silence Duration") },
        description = { Text("Silence duration that ends a turn, in milliseconds") }
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
        label = { Text("API Key") },
        description = { Text("Volcengine API key (X-Api-Key)") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-api-key") }
        )
    }

    FormItem(
        label = { Text("WebSocket URL") },
        description = { Text("Volcengine streaming ASR endpoint") }
    ) {
        OutlinedTextField(
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel") }
        )
    }

    FormItem(
        label = { Text("Resource ID") },
        description = { Text("X-Api-Resource-Id for billing") }
    ) {
        OutlinedTextField(
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("volc.bigasr.sauc.duration") }
        )
    }

    FormItem(
        label = { Text("Language") },
        description = { Text("Optional language code, e.g. zh-CN, en-US, ja-JP") }
    ) {
        OutlinedTextField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }
}
