package me.rerere.rikkahub.data.asr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ASRProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
    ): ASRProviderSetting

    @Serializable
    @SerialName("openai_realtime")
    data class OpenAIRealtime(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenAI Realtime ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://api.openai.com/v1/realtime?intent=transcription",
        val model: String = "gpt-4o-transcribe",
        val language: String = "",
        val prompt: String = "",
        val sampleRate: Int = 24000,
        val vadThreshold: Float = 0.5f,
        val prefixPaddingMs: Int = 300,
        val silenceDurationMs: Int = 500,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return copy(id = id, name = name)
        }
    }

    @Serializable
    @SerialName("dashscope")
    data class DashScope(
        override val id: Uuid = Uuid.random(),
        override val name: String = "DashScope ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        val model: String = "qwen3-asr-flash-realtime-2026-02-10",
        val language: String = "zh",
        val sampleRate: Int = 16000,
        val vadThreshold: Float = 0.2f,
        val silenceDurationMs: Int = 800,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return copy(id = id, name = name)
        }
    }

    companion object {
        val Types by lazy {
            listOf(OpenAIRealtime::class, DashScope::class)
        }
    }
}
