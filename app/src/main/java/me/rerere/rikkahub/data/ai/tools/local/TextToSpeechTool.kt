package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

internal fun buildTextToSpeechTool(
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "text_to_speech",
    description = """
        Speak text aloud to the user using the device's text-to-speech engine.
        Use this when the user asks you to read something aloud, or when audio output is appropriate.
        The tool returns immediately; audio plays in the background on the device.
        Provide natural, readable text without markdown formatting.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        // 当前选中的 TTS provider 若硬编码了语气标记引导，则注入 system prompt（否则为空）
        settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?.let { ttsManager.getPromptGuidance(it) }
            .orEmpty()
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to speak aloud")
                })
            },
            required = listOf("text")
        )
    },
    execute = {
        val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: error("text is required")
        eventBus.emit(AppEvent.Speak(text))
        val payload = buildJsonObject {
            put("success", true)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
