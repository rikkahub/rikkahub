package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.TokenUsage

/**
 * Provider-independent streaming events.
 *
 * Providers are responsible for translating their wire protocol into these events. Consumers can therefore process
 * text, reasoning, tool calls and images without knowing which provider produced them.
 */
@Serializable
sealed class StreamChunk {
    @Serializable
    @SerialName("text_start")
    data class TextStart(val id: String) : StreamChunk()

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val id: String, val text: String) : StreamChunk()

    @Serializable
    @SerialName("text_end")
    data class TextEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("reasoning_start")
    data class ReasoningStart(
        val id: String,
        val metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        val id: String,
        val text: String,
        val metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning_end")
    data class ReasoningEnd(
        val id: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_start")
    data class ToolCallStart(
        val id: String,
        val toolName: String = "",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_delta")
    data class ToolCallDelta(
        val id: String,
        val toolNameDelta: String = "",
        val inputDelta: String = "",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_end")
    data class ToolCallEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("server_tool_start")
    data class ServerToolStart(
        val id: String,
        val toolName: String,
        val input: JsonElement? = null,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_input_delta")
    data class ServerToolInputDelta(
        val id: String,
        val inputDelta: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_input_end")
    data class ServerToolInputEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("server_tool_end")
    data class ServerToolEnd(
        val id: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_start")
    data class ImageStart(
        val id: String,
        val mimeType: String = "image/png",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_delta")
    data class ImageDelta(
        val id: String,
        val data: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    /** A complete renderable image snapshot that replaces previously received data for the same id. */
    @Serializable
    @SerialName("image_snapshot")
    data class ImageSnapshot(
        val id: String,
        val data: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_end")
    data class ImageEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("annotations")
    data class Annotations(val annotations: List<UIMessageAnnotation>) : StreamChunk()

    @Serializable
    @SerialName("usage")
    data class Usage(val usage: TokenUsage) : StreamChunk()

    @Serializable
    @SerialName("finish")
    data class Finish(
        val finishReason: String? = null,
        val responseId: String? = null,
        val model: String? = null,
    ) : StreamChunk()
}
