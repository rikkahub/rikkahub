package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock

internal class ChatCompletionsStreamDecoder : StreamChunkDecoder {
    private val streamState = ChatCompletionsStreamState()
    private val toolIdsByIndex = mutableMapOf<Int, String>()
    private val reasoningDetailsByIndex = linkedMapOf<Int, JsonObject>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val chunks = buildList {
            event.data.trim().split("\n").filter(String::isNotBlank).forEach { line ->
                val payload = json.parseToJsonElement(line).jsonObject
                payload["error"]?.let { throw it.parseErrorDetail() }
                responseId = payload["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                responseModel = payload["model"]?.jsonPrimitive?.contentOrNull ?: responseModel

                payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
                    (choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject)?.let { message ->
                        val messageWithoutTools = JsonObject(message.filterKeys { it != "tool_calls" })
                        addAll(streamState.append(parseMessage(messageWithoutTools), responseId))

                        message["tool_calls"]?.jsonArray?.forEachIndexed { fallbackIndex, element ->
                            val toolCall = element.jsonObject
                            val index = toolCall["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                            val toolId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                                ?.also { toolIdsByIndex[index] = it }
                                ?: toolIdsByIndex.getOrPut(index) {
                                    "${responseId ?: "response"}:tool-$index"
                                }
                            val function = toolCall["function"]?.jsonObject
                            addAll(streamState.append(
                                UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(UIMessagePart.Tool(
                                        toolCallId = toolId,
                                        toolName = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                                        input = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "",
                                        output = emptyList(),
                                    )),
                                ),
                                responseId,
                            ))
                        }
                    }
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                }
                parseUsage(payload["usage"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            }
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return streamState.finish(finishReason, responseId, responseModel)
    }

    private fun parseMessage(payload: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            payload["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )
        val content = payload["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val reasoning = payload["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["content"]?.takeIf { it is JsonArray }?.let { array ->
                array.jsonArrayOrNull?.getOrNull(0)?.jsonObject
                    ?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull
                    ?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
            }
        val reasoningMetadata = accumulateReasoningDetails(payload["reasoning_details"]?.jsonArrayOrNull)
        val images = payload["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(UIMessagePart.Reasoning(
                        reasoning = reasoning.orEmpty(),
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = reasoningMetadata,
                    ))
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    if (imageObject["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull
                        ?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(payload["annotations"]?.jsonArrayOrNull ?: JsonArray(emptyList())),
        )
    }

    private fun accumulateReasoningDetails(details: JsonArray?): JsonObject? {
        if (details == null) return null
        details.forEachIndexed { fallbackIndex, element ->
            val incoming = element.jsonObject
            val index = incoming["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
            reasoningDetailsByIndex[index] = mergeReasoningDetail(reasoningDetailsByIndex[index], incoming)
        }
        return OpenRouterReasoningMetadata(
            reasoningDetails = JsonArray(reasoningDetailsByIndex.toSortedMap().values.toList()),
        ).toMetadata()
    }

    private fun mergeReasoningDetail(existing: JsonObject?, incoming: JsonObject): JsonObject = buildJsonObject {
        existing?.forEach { (key, value) -> put(key, value) }
        incoming.forEach { (key, value) ->
            val delta = value.jsonPrimitiveOrNull?.contentOrNull
            if (existing != null && key in REASONING_DETAIL_DELTA_FIELDS && delta != null) {
                val previous = existing[key]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                put(key, JsonPrimitive(previous + delta))
            } else {
                put(key, value)
            }
        }
    }

    private fun parseAnnotations(array: JsonArray): List<UIMessageAnnotation> = array.map { element ->
        val type = element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
        when (type) {
            "url_citation" -> UIMessageAnnotation.UrlCitation(
                title = element.jsonObject["url_citation"]?.jsonObject
                    ?.get("title")?.jsonPrimitive?.contentOrNull ?: "",
                url = element.jsonObject["url_citation"]?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull ?: "",
            )
            else -> error("unknown annotation type: $type")
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["prompt_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0,
        )
    }

    private class ChatCompletionsStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null
        private val openToolIds = linkedSetOf<String>()
        private var lastToolId: String? = null

        fun append(message: UIMessage, sourceId: String?): List<StreamChunk> = buildList {
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                        addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
                        val id = textId ?: nextId(sourceId, "text").also {
                            textId = it; add(StreamChunk.TextStart(it))
                        }
                        add(StreamChunk.TextDelta(id, part.text))
                    }
                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText()); addAll(closeImage()); addAll(closeTools())
                        val id = reasoningId ?: nextId(sourceId, "reasoning").also {
                            reasoningId = it; add(StreamChunk.ReasoningStart(it, part.metadata))
                        }
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                    }
                    is UIMessagePart.Tool -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                        val id = part.toolCallId.ifBlank { lastToolId ?: nextId(sourceId, "tool") }
                        var nameDelta = part.toolName
                        if (openToolIds.add(id)) {
                            nameDelta = ""
                            add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                        }
                        lastToolId = id
                        if (nameDelta.isNotEmpty() || part.input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(id, nameDelta, part.input, part.metadata))
                        }
                    }
                    is UIMessagePart.Image -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeTools())
                        val id = imageId ?: nextId(sourceId, "image").also {
                            imageId = it; add(StreamChunk.ImageStart(it, metadata = part.metadata))
                        }
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                    }
                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        fun finish(reason: String?, responseId: String?, model: String?): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()
        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun closeTools() = openToolIds.toList().map { StreamChunk.ToolCallEnd(it) }.also {
            openToolIds.clear(); lastToolId = null
        }
        private fun nextId(sourceId: String?, kind: String): String =
            "${sourceId?.takeIf(String::isNotBlank)?.let { "$it:" }.orEmpty()}$kind-${++sequence}"
    }

    private companion object {
        val REASONING_DETAIL_DELTA_FIELDS = setOf("text", "summary", "data", "signature")
    }
}
