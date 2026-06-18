package me.rerere.asr

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
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("dashscope")
    data class DashScope(
        override val id: Uuid = Uuid.random(),
        override val name: String = "DashScope ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
        val model: String = "qwen3-asr-flash-realtime",
        val language: String = "",
        val sampleRate: Int = 16000,
        val vadThreshold: Float = 0.2f,
        val silenceDurationMs: Int = 800,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Volcengine ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel",
        val resourceId: String = "volc.seedasr.sauc.duration",
        val language: String = "",
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 小米 MiMo ASR (mimo-v2.5-asr)。
     *
     * 与 OpenAIRealtime / DashScope / Volcengine 不同, MiMo ASR 是基于 OpenAI 兼容
     * chat/completions 的 HTTP 一次性识别接口, 不是 WebSocket 流式接口。
     * 客户端在录音期间按 [segmentDurationSec] 分段, 把每段 PCM 转成 WAV 后 base64
     * 内嵌到 messages[].content[].input_audio.data 字段, POST 到
     * {baseUrl}/chat/completions, 返回结果在 choices[0].message.content。
     *
     * 官方文档: https://platform.xiaomimimo.com/docs/zh-CN/api/audio/Speech-Recognition
     */
    @Serializable
    @SerialName("mimo")
    data class MiMo(
        override val id: Uuid = Uuid.random(),
        override val name: String = "MiMo ASR",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2.5-asr",
        // auto | zh | en; 留空时不下发 asr_options, 服务端默认 auto
        val language: String = "auto",
        val sampleRate: Int = 16000,
        // 每多少秒自动 flush 一次当前缓冲区 (上传识别)。设为 0 表示禁用自动分段,
        // 仅在用户主动 stop() 时整体上传 (注意 MiMo 单次请求 raw 上限约 7.5MB,
        // 16kHz/16bit/mono 下约 234 秒)。
        val segmentDurationSec: Int = 30,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAIRealtime::class,
                DashScope::class,
                Volcengine::class,
                MiMo::class,
            )
        }
    }
}
