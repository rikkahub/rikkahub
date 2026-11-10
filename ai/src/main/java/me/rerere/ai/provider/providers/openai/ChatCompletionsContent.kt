package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

internal data class ChatCompletionsContent(
    val text: String,
    val reasoning: String?,
)

/**
 * 解析响应 / delta 里的 content。
 *
 * 一般是字符串；Mistral 开启推理时是块列表，thinking 块的 thinking 字段又是 text 块列表。
 * 流式时思考结束的那一帧会同时带收尾的 thinking 块和回答的第一个 text 块，所以要遍历所有块。
 * https://docs.mistral.ai/capabilities/reasoning
 */
internal fun parseChatCompletionsContent(content: JsonElement?): ChatCompletionsContent {
    content?.jsonPrimitiveOrNull?.contentOrNull?.let { return ChatCompletionsContent(it, null) }
    val chunks = content?.jsonArrayOrNull ?: return ChatCompletionsContent("", null)

    val text = StringBuilder()
    val reasoning = StringBuilder()
    chunks.forEach { element ->
        val chunk = element.jsonObjectOrNull ?: return@forEach
        when (chunk["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
            "text" -> text.append(chunk["text"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
            "thinking" -> chunk["thinking"]?.jsonArrayOrNull?.forEach { inner ->
                val innerChunk = inner.jsonObjectOrNull ?: return@forEach
                if (innerChunk["type"]?.jsonPrimitiveOrNull?.contentOrNull == "text") {
                    reasoning.append(innerChunk["text"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                }
            }
        }
    }
    return ChatCompletionsContent(text.toString(), reasoning.toString().ifEmpty { null })
}
