package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        return choice?.delta?.let { delta ->
            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        val existingTextPart =
                            acc.find { it is UIMessagePart.Text } as? UIMessagePart.Text
                        if (existingTextPart != null) {
                            acc.map { part ->
                                if (part is UIMessagePart.Text) {
                                    UIMessagePart.Text(existingTextPart.text + deltaPart.text)
                                } else part
                            }
                        } else {
                            acc + deltaPart
                        }
                    }

                    is UIMessagePart.Image -> {
                        val existingImagePart =
                            acc.find { it is UIMessagePart.Image } as? UIMessagePart.Image
                        if (existingImagePart != null) {
                            acc.map { part ->
                                if (part is UIMessagePart.Image) {
                                    UIMessagePart.Image(
                                        url = existingImagePart.url + deltaPart.url,
                                    )
                                } else part
                            }
                        } else {
                            acc + UIMessagePart.Image(
                                url = "data:image/png;base64,${deltaPart.url}",
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        val existingReasoningPart =
                            acc.find { it is UIMessagePart.Reasoning } as? UIMessagePart.Reasoning
                        if (existingReasoningPart != null) {
                            acc.map { part ->
                                if (part is UIMessagePart.Reasoning) {
                                    UIMessagePart.Reasoning(
                                        reasoning = existingReasoningPart.reasoning + deltaPart.reasoning,
                                        createdAt = existingReasoningPart.createdAt,
                                        finishedAt = null,
                                    ).also {
                                        if (deltaPart.metadata != null) {
                                            it.metadata = deltaPart.metadata // 更新metadata
                                            println("更新metadata: ${json.encodeToString(deltaPart)}")
                                        }
                                    }
                                } else part
                            }
                        } else {
                            acc + deltaPart
                        }
                    }

                    is UIMessagePart.ToolCall -> {
                        if (deltaPart.toolCallId.isBlank()) {
                            val lastToolCall =
                                acc.lastOrNull { it is UIMessagePart.ToolCall } as? UIMessagePart.ToolCall
                            if (lastToolCall == null || lastToolCall.toolCallId.isBlank()) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part == lastToolCall && part is UIMessagePart.ToolCall) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        } else {
                            // insert or update
                            val existsPart = acc.find {
                                it is UIMessagePart.ToolCall && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.ToolCall
                            if (existsPart == null) {
                                // insert
                                acc + deltaPart.copy()
                            } else {
                                // update
                                acc.map { part ->
                                    if (part is UIMessagePart.ToolCall && part.toolCallId == deltaPart.toolCallId) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        }
                    }

                    else -> {
                        println("delta part append not supported: $deltaPart")
                        acc
                    }
                }
            }
            // Handle Reasoning End
            if (parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isNotEmpty() && delta.parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isEmpty()
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
            )
        } ?: this
    }

    fun summaryAsText(): String {
        return "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getToolCalls() = parts.filterIsInstance<UIMessagePart.ToolCall>()

    fun getToolResults() = parts.filterIsInstance<UIMessagePart.ToolResult>()

    fun isValidToUpload() = parts.any {
        it !is UIMessagePart.Reasoning
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(chunk: MessageChunk, model: Model? = null): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: throw Exception("delta/message is null")
    if (this.last().role != message.role) {
        return this + message.copy(modelId = model?.id)
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

fun List<UIMessage>.truncate(index: Int): List<UIMessage> {
    if (index < 0 || index > this.lastIndex) return this
    return this.subList(index, this.size)
}

fun List<UIMessage>.limitContext(size: Int): List<UIMessage> {
    if (size <= 0 || this.size <= size) return this

    val startIndex = this.size - size
    var adjustedStartIndex = startIndex

    // 循环往前查找，直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含tool result，往前查找对应的tool call
        if (currentMessage.getToolResults().isNotEmpty()) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getToolCalls().isNotEmpty()) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含tool call，往前查找对应的用户消息
        if (currentMessage.getToolCalls().isNotEmpty()) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return this.subList(adjustedStartIndex, this.size)
}

@Serializable
sealed class UIMessagePart {
    abstract val priority: Int
    abstract val metadata: JsonObject?

    @Serializable
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 0
    }

    @Serializable
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = -1
    }

    @Deprecated("Deprecated")
    @Serializable
    data object Search : UIMessagePart() {
        override val priority: Int = 0
        override var metadata: JsonObject? = null
    }

    @Serializable
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                metadata = if(other.metadata != null) other.metadata else metadata,
            )
        }

        override val priority: Int = 0
    }

    @Serializable
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 0
    }
}

fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    return sortedBy { it.priority }
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: TokenUsage? = null,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
