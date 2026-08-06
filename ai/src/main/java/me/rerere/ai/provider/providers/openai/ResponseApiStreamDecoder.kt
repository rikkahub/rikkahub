package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.common.http.jsonObjectOrNull

internal class ResponseApiStreamDecoder : StreamChunkDecoder {
    private val state = ResponseStreamState()

    override fun accept(event: SseEvent): DecodeResult {
        if (state.finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(state.finish(), completed = true)

        val payload = json.parseToJsonElement(event.data).jsonObject
        val chunks = parseEvent(payload)
        val completed = payload["type"]?.jsonPrimitive?.contentOrNull == "response.completed" ||
            event.event == "response.completed"
        return DecodeResult(chunks, completed)
    }

    override fun onClosed(): List<StreamChunk> = state.finish()

    private fun parseEvent(payload: JsonObject): List<StreamChunk> {
        val chunkType = payload["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
        val itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull
        val contentIndex = payload["content_index"]?.jsonPrimitive?.intOrNull ?: 0
        val summaryIndex = payload["summary_index"]?.jsonPrimitive?.intOrNull ?: contentIndex
        val textId = itemId?.let { "$it:text:$contentIndex" }
        val reasoningId = itemId?.let { "$it:reasoning:$summaryIndex" }

        return when (chunkType) {
            "response.output_text.delta" -> state.textDelta(
                textId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> state.reasoningDelta(
                reasoningId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
            )
            "response.content_part.added" -> {
                val part = payload["part"]?.jsonObject ?: return emptyList()
                if (part["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    state.startText(textId ?: error("item_id not found"))
                } else emptyList()
            }
            "response.content_part.done", "response.output_text.done" ->
                state.endText(textId ?: error("item_id not found"))
            "response.reasoning_summary_part.added" -> state.startReasoning(
                reasoningId ?: error("item_id not found"),
                state.reasoningMetadata[itemId],
            )
            "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.done",
            "response.reasoning_text.done" -> emptyList()
            "response.output_item.added" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                when (type) {
                    "function_call" -> {
                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: id
                        state.toolCallIdsByItemId[id] = callId
                        state.startTool(
                            id = callId,
                            name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            initialInput = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    }
                    "image_generation_call" -> state.startImage(id)
                    "reasoning" -> {
                        state.reasoningMetadata[id] = OpenAIReasoningMetadata(
                            reasoningId = id,
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
            "response.output_item.done" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                when (type) {
                    "reasoning" -> {
                        val metadata = OpenAIReasoningMetadata(
                            reasoningId = id,
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        state.reasoningMetadata[id] = metadata
                        state.endReasoningItem(id, metadata)
                    }
                    "image_generation_call" -> buildList {
                        addAll(state.startImage(id))
                        item["result"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                            add(StreamChunk.ImageSnapshot(id, it))
                        }
                        addAll(state.endImage(id))
                    }
                    "function_call" -> state.endTool(state.toolCallIdsByItemId.remove(id) ?: id)
                    else -> emptyList()
                }
            }
            "response.function_call_arguments.delta" -> {
                val requiredItemId = itemId ?: error("item_id not found")
                state.toolDelta(
                    state.toolCallIdsByItemId[requiredItemId] ?: requiredItemId,
                    payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            "response.function_call_arguments.done" -> {
                val requiredItemId = itemId ?: error("item_id not found")
                val toolCallId = state.toolCallIdsByItemId[requiredItemId] ?: requiredItemId
                buildList {
                    if (toolCallId !in state.toolIdsWithInput) {
                        addAll(state.toolDelta(
                            toolCallId,
                            payload["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                    }
                    addAll(state.endTool(toolCallId))
                }
            }
            "response.image_generation_call.partial_image" -> {
                val requiredItemId = itemId ?: error("item_id not found")
                buildList {
                    addAll(state.startImage(requiredItemId))
                    add(StreamChunk.ImageSnapshot(
                        requiredItemId,
                        payload["partial_image_b64"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                }
            }
            "response.completed" -> {
                val response = payload["response"]?.jsonObject
                buildList {
                    parseUsage(response?.get("usage") as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
                    addAll(state.finish(
                        finishReason = response?.get("status")?.jsonPrimitive?.contentOrNull,
                        responseId = response?.get("id")?.jsonPrimitive?.contentOrNull,
                        model = response?.get("model")?.jsonPrimitive?.contentOrNull,
                    ))
                }
            }
            else -> emptyList()
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["input_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private class ResponseStreamState {
        val toolCallIdsByItemId = mutableMapOf<String, String>()
        val toolIdsWithInput = mutableSetOf<String>()
        val reasoningMetadata = mutableMapOf<String, JsonObject>()
        private val openTextIds = linkedSetOf<String>()
        private val openReasoningIds = linkedSetOf<String>()
        private val openImageIds = linkedSetOf<String>()
        private val openToolIds = linkedSetOf<String>()
        var finished = false
            private set

        fun startText(id: String) = if (openTextIds.add(id)) listOf(StreamChunk.TextStart(id)) else emptyList()
        fun textDelta(id: String, text: String) = startText(id) + StreamChunk.TextDelta(id, text)
        fun endText(id: String) = if (openTextIds.remove(id)) listOf(StreamChunk.TextEnd(id)) else emptyList()
        fun startReasoning(id: String, metadata: JsonObject?) =
            if (openReasoningIds.add(id)) listOf(StreamChunk.ReasoningStart(id, metadata)) else emptyList()
        fun reasoningDelta(id: String, text: String, metadata: JsonObject?) =
            startReasoning(id, metadata) + StreamChunk.ReasoningDelta(id, text, metadata)
        fun endReasoning(id: String, metadata: JsonObject?) =
            if (openReasoningIds.remove(id)) listOf(StreamChunk.ReasoningEnd(id, metadata)) else emptyList()

        fun endReasoningItem(itemId: String, metadata: JsonObject?): List<StreamChunk> {
            reasoningMetadata.remove(itemId)
            val ids = openReasoningIds.filter { it.startsWith("$itemId:reasoning:") }
            if (ids.isNotEmpty()) return ids.flatMap { endReasoning(it, metadata) }

            // encrypted_content 可以在 summary 为空时单独出现，仍需物化 metadata-only reasoning part。
            val id = "$itemId:reasoning:0"
            return startReasoning(id, metadata) + endReasoning(id, metadata)
        }

        fun startImage(id: String) = if (openImageIds.add(id)) listOf(StreamChunk.ImageStart(id)) else emptyList()
        fun endImage(id: String) = if (openImageIds.remove(id)) listOf(StreamChunk.ImageEnd(id)) else emptyList()
        fun startTool(id: String, name: String, initialInput: String): List<StreamChunk> = buildList {
            if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id, name))
            if (initialInput.isNotEmpty()) addAll(toolDelta(id, initialInput))
        }
        fun toolDelta(id: String, input: String): List<StreamChunk> = buildList {
            if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id))
            if (input.isNotEmpty()) {
                toolIdsWithInput += id
                add(StreamChunk.ToolCallDelta(id, inputDelta = input))
            }
        }
        fun endTool(id: String) = if (openToolIds.remove(id)) {
            toolIdsWithInput.remove(id)
            listOf(StreamChunk.ToolCallEnd(id))
        } else emptyList()

        fun finish(
            finishReason: String? = null,
            responseId: String? = null,
            model: String? = null,
        ): List<StreamChunk> {
            if (finished) return emptyList()
            finished = true
            return buildList {
                openTextIds.toList().forEach { addAll(endText(it)) }
                reasoningMetadata.toMap().forEach { (itemId, metadata) ->
                    addAll(endReasoningItem(itemId, metadata))
                }
                openReasoningIds.toList().forEach { addAll(endReasoning(it, null)) }
                openImageIds.toList().forEach { addAll(endImage(it)) }
                openToolIds.toList().forEach { addAll(endTool(it)) }
                add(StreamChunk.Finish(finishReason, responseId, model))
            }
        }
    }
}
