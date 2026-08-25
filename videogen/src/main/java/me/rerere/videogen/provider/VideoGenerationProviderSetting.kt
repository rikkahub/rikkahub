package me.rerere.videogen.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class VideoGenerationProviderSetting {
    abstract val apiKey: String
    abstract val baseUrl: String
    abstract val model: String

    @Serializable
    @SerialName("aliyun")
    data class Aliyun(
        override val apiKey: String = "",
        override val baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
        override val model: String = "wan3.0-video",
    ) : VideoGenerationProviderSetting()

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val apiKey: String = "",
        override val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
        override val model: String = "doubao-seedance-2-0-260128",
    ) : VideoGenerationProviderSetting()

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.minimaxi.com/v2",
        override val model: String = "MiniMax-H3",
    ) : VideoGenerationProviderSetting()
}
