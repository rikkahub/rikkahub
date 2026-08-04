package me.rerere.ai.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import kotlin.time.Clock

/** 将通用流事件合并到消息列表。 */
fun List<UIMessage>.handleStreamChunk(chunk: StreamChunk, model: Model? = null): List<UIMessage> {
    require(isNotEmpty()) { "messages must not be empty" }

    val messages = if (last().role != MessageRole.ASSISTANT) {
        this + UIMessage(modelId = model?.id, role = MessageRole.ASSISTANT, parts = emptyList())
    } else {
        this
    }

    val updatedMessage = messages.last().appendStreamChunk(chunk)
    return messages.dropLast(1) + updatedMessage
}

fun List<UIMessage>.handleTextGenerationResult(
    result: TextGenerationResult,
    model: Model? = null,
): List<UIMessage> {
    require(isNotEmpty()) { "messages must not be empty" }
    val incoming = result.message.copy(
        modelId = model?.id,
        usage = result.usage,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    ).finishReasoning()
    return if (last().role != incoming.role) {
        this + incoming
    } else {
        dropLast(1) + last().appendMessage(incoming).copy(
            modelId = model?.id ?: last().modelId,
            usage = last().usage.merge(result.usage ?: TokenUsage()),
            finishedAt = incoming.finishedAt,
        ).finishReasoning()
    }
}

private fun UIMessage.appendStreamChunk(chunk: StreamChunk): UIMessage {
    return when (chunk) {
        is StreamChunk.TextStart -> copy(parts = parts + UIMessagePart.Text(""))
        is StreamChunk.TextDelta -> {
            val index = parts.lastIndex.takeIf { parts.lastOrNull() is UIMessagePart.Text } ?: -1
            if (index < 0) {
                copy(parts = parts + UIMessagePart.Text(chunk.text))
            } else {
                copy(parts = parts.toMutableList().apply {
                    val text = get(index) as UIMessagePart.Text
                    set(index, text.copy(text = text.text + chunk.text))
                })
            }
        }

        is StreamChunk.TextEnd -> this
        is StreamChunk.ReasoningStart -> copy(
            parts = parts + UIMessagePart.Reasoning(
                reasoning = "",
                createdAt = Clock.System.now(),
                finishedAt = null,
                metadata = chunk.metadata,
            )
        )

        is StreamChunk.ReasoningDelta -> {
            val index = parts.lastIndex.takeIf { parts.lastOrNull() is UIMessagePart.Reasoning } ?: -1
            if (index < 0) {
                copy(
                    parts = parts + UIMessagePart.Reasoning(
                        reasoning = chunk.text,
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = chunk.metadata,
                    )
                )
            } else {
                copy(parts = parts.toMutableList().apply {
                    val reasoning = get(index) as UIMessagePart.Reasoning
                    set(
                        index,
                        reasoning.copy(
                            reasoning = reasoning.reasoning + chunk.text,
                            metadata = chunk.metadata ?: reasoning.metadata,
                        )
                    )
                })
            }
        }

        is StreamChunk.ReasoningEnd -> copy(parts = parts.map { part ->
            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                part.copy(
                    finishedAt = Clock.System.now(),
                    metadata = chunk.metadata ?: part.metadata,
                )
            } else {
                part
            }
        })

        is StreamChunk.ToolCallStart -> copy(
            parts = parts + UIMessagePart.Tool(
                toolCallId = chunk.id,
                toolName = chunk.toolName,
                input = "",
                metadata = chunk.metadata,
            )
        )

        is StreamChunk.ToolCallDelta -> copy(parts = parts.map { part ->
            if (part is UIMessagePart.Tool && part.toolCallId == chunk.id) {
                part.copy(
                    toolName = part.toolName + chunk.toolNameDelta,
                    input = part.input + chunk.inputDelta,
                    metadata = chunk.metadata ?: part.metadata,
                )
            } else {
                part
            }
        })

        is StreamChunk.ToolCallEnd -> this
        is StreamChunk.ImageStart -> copy(
            parts = parts + UIMessagePart.Image(
                url = "data:${chunk.mimeType};base64,",
                metadata = chunk.metadata,
            )
        )

        is StreamChunk.ImageDelta -> {
            val index = parts.lastIndex.takeIf { parts.lastOrNull() is UIMessagePart.Image } ?: -1
            if (index < 0) {
                copy(parts = parts + UIMessagePart.Image(chunk.data, chunk.metadata))
            } else {
                copy(parts = parts.toMutableList().apply {
                    val image = get(index) as UIMessagePart.Image
                    set(
                        index,
                        image.copy(
                            url = image.url + chunk.data,
                            metadata = chunk.metadata ?: image.metadata,
                        )
                    )
                })
            }
        }

        is StreamChunk.ImageEnd -> this
        is StreamChunk.Annotations -> copy(
            annotations = (annotations + chunk.annotations).distinct()
        )

        is StreamChunk.Usage -> copy(usage = usage.merge(chunk.usage))
        is StreamChunk.Finish -> copy(
            finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        ).finishReasoning()
    }
}

private fun UIMessage.appendMessage(delta: UIMessage): UIMessage {
    var newParts = delta.parts.fold(parts) { acc, deltaPart ->
        when (deltaPart) {
            is UIMessagePart.Text -> {
                if (deltaPart.text.isEmpty()) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (lastPart is UIMessagePart.Text) {
                        acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                    } else {
                        acc + deltaPart
                    }
                }
            }

            is UIMessagePart.Image -> {
                val lastPart = acc.lastOrNull()
                if (lastPart is UIMessagePart.Image) {
                    acc.dropLast(1) + lastPart.copy(
                        url = lastPart.url + deltaPart.url,
                        metadata = deltaPart.metadata ?: lastPart.metadata,
                    )
                } else {
                    acc + UIMessagePart.Image(
                        url = "data:image/png;base64,${deltaPart.url}",
                        metadata = deltaPart.metadata,
                    )
                }
            }

            is UIMessagePart.Reasoning -> {
                if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (lastPart is UIMessagePart.Reasoning) {
                        acc.dropLast(1) + UIMessagePart.Reasoning(
                            reasoning = lastPart.reasoning + deltaPart.reasoning,
                            createdAt = lastPart.createdAt,
                            finishedAt = null,
                            metadata = deltaPart.metadata ?: lastPart.metadata,
                        )
                    } else {
                        acc + deltaPart
                    }
                }
            }

            is UIMessagePart.Tool -> {
                if (deltaPart.toolCallId.isBlank()) {
                    val lastTool = acc.lastOrNull { it is UIMessagePart.Tool } as? UIMessagePart.Tool
                    if (lastTool != null) {
                        acc.map { part -> if (part === lastTool) part.merge(deltaPart) else part }
                    } else {
                        acc + deltaPart.copy()
                    }
                } else {
                    val existingPart = acc.find {
                        it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                    } as? UIMessagePart.Tool
                    if (existingPart == null) {
                        acc + deltaPart.copy()
                    } else {
                        acc.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                part.merge(deltaPart)
                            } else {
                                part
                            }
                        }
                    }
                }
            }

            else -> acc
        }
    }

    if (parts.filterIsInstance<UIMessagePart.Reasoning>().isNotEmpty() &&
        delta.parts.filterIsInstance<UIMessagePart.Reasoning>().isEmpty()
    ) {
        newParts = newParts.map { part ->
            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                part.copy(finishedAt = Clock.System.now())
            } else {
                part
            }
        }
    }

    return copy(
        parts = newParts,
        annotations = delta.annotations.ifEmpty { annotations },
    )
}
