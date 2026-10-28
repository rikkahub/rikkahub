package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
    ;

    companion object {
        // 宽松匹配 role 字符串，部分中转会返回空字符串或非标准值，匹配不到时返回 null
        fun fromValueOrNull(value: String?): MessageRole? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
