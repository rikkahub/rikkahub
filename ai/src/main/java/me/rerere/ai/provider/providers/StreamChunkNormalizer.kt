package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Converts provider message deltas into explicit, provider-independent stream event lifecycles. */
internal class StreamChunkNormalizer {
    private var sequence = 0
    private var textId: String? = null
    private var reasoningId: String? = null
    private var imageId: String? = null
    private val openToolIds = linkedSetOf<String>()
    private val toolIdsWithInput = mutableSetOf<String>()
    private var lastToolId: String? = null

    fun append(message: UIMessage, sourceId: String? = null): List<StreamChunk> = buildList {
        val imageCount = message.parts.count { it is UIMessagePart.Image }
        var emittedImages = 0

        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    if (part.text.isEmpty()) return@forEach
                    addAll(closeReasoning())
                    addAll(closeImage())
                    addAll(closeTools())
                    val id = textId ?: nextId(sourceId, "text").also {
                        textId = it
                        add(StreamChunk.TextStart(it))
                    }
                    add(StreamChunk.TextDelta(id, part.text))
                }

                is UIMessagePart.Reasoning -> {
                    if (part.reasoning.isEmpty() && part.metadata == null) return@forEach
                    addAll(closeText())
                    addAll(closeImage())
                    addAll(closeTools())
                    val id = reasoningId ?: nextId(sourceId, "reasoning").also {
                        reasoningId = it
                        add(StreamChunk.ReasoningStart(it, part.metadata))
                    }
                    if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                    }
                }

                is UIMessagePart.Tool -> {
                    addAll(closeText())
                    addAll(closeReasoning())
                    addAll(closeImage())
                    val id = part.toolCallId.ifBlank {
                        lastToolId ?: nextId(sourceId, "tool")
                    }
                    var toolNameDelta = part.toolName
                    if (id !in openToolIds) {
                        openToolIds += id
                        toolNameDelta = ""
                        add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                    }
                    lastToolId = id
                    if (toolNameDelta.isNotEmpty() || part.input.isNotEmpty()) {
                        if (part.input.isNotEmpty()) toolIdsWithInput += id
                        add(
                            StreamChunk.ToolCallDelta(
                                id = id,
                                toolNameDelta = toolNameDelta,
                                inputDelta = part.input,
                                metadata = part.metadata,
                            )
                        )
                    }
                }

                is UIMessagePart.Image -> {
                    addAll(closeText())
                    addAll(closeReasoning())
                    addAll(closeTools())
                    if (imageCount > 1 && emittedImages > 0) addAll(closeImage())
                    val id = imageId ?: nextId(sourceId, "image").also {
                        imageId = it
                        add(StreamChunk.ImageStart(it, metadata = part.metadata))
                    }
                    val data = part.url.substringAfter(";base64,", part.url)
                    if (data.isNotEmpty() || part.metadata != null) {
                        add(StreamChunk.ImageDelta(id, data, part.metadata))
                    }
                    emittedImages++
                }

                else -> Unit
            }
        }

        if (message.annotations.isNotEmpty()) {
            add(StreamChunk.Annotations(message.annotations))
        }
    }

    fun closeText(): List<StreamChunk> = textId?.let {
        textId = null
        listOf(StreamChunk.TextEnd(it))
    }.orEmpty()

    fun closeReasoning(metadata: JsonObject? = null): List<StreamChunk> = reasoningId?.let {
        reasoningId = null
        listOf(StreamChunk.ReasoningEnd(it, metadata))
    }.orEmpty()

    fun closeImage(): List<StreamChunk> = imageId?.let {
        imageId = null
        listOf(StreamChunk.ImageEnd(it))
    }.orEmpty()

    fun closeTool(id: String): List<StreamChunk> {
        if (!openToolIds.remove(id)) return emptyList()
        toolIdsWithInput.remove(id)
        if (lastToolId == id) lastToolId = openToolIds.lastOrNull()
        return listOf(StreamChunk.ToolCallEnd(id))
    }

    fun hasToolInput(id: String): Boolean = id in toolIdsWithInput

    fun finish(
        finishReason: String? = null,
        responseId: String? = null,
        model: String? = null,
    ): List<StreamChunk> = buildList {
        addAll(closeText())
        addAll(closeReasoning())
        addAll(closeImage())
        addAll(closeTools())
        add(StreamChunk.Finish(finishReason, responseId, model))
    }

    private fun closeTools(): List<StreamChunk> = openToolIds.toList().map { id ->
        openToolIds.remove(id)
        toolIdsWithInput.remove(id)
        StreamChunk.ToolCallEnd(id)
    }.also {
        lastToolId = null
    }

    private fun nextId(sourceId: String?, kind: String): String {
        sequence++
        return buildString {
            if (!sourceId.isNullOrBlank()) {
                append(sourceId)
                append(':')
            }
            append(kind)
            append('-')
            append(sequence)
        }
    }
}
