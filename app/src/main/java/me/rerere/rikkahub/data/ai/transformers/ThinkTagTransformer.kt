package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexApplyPhase
import me.rerere.rikkahub.data.model.chatMessageDepthFromEndMap
import me.rerere.rikkahub.data.model.replaceRegexes
import kotlin.time.Clock

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CLOSING_TAG_REGEX = Regex("</think>")

internal fun shouldParseThinkTagAfterVisualRegex(
    text: String,
    assistant: Assistant,
    messageDepthFromEnd: Int?,
    enableThinkRegex: Boolean,
): Boolean {
    if (!enableThinkRegex) {
        return THINKING_REGEX.containsMatchIn(text)
    }
    val textAfterVisualRegex = text.replaceRegexes(
        assistant = assistant,
        scope = AssistantAffectScope.ASSISTANT,
        phase = AssistantRegexApplyPhase.VISUAL_ONLY,
        messageDepthFromEnd = messageDepthFromEnd
    )
    return THINKING_REGEX.containsMatchIn(textAfterVisualRegex)
}

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val depthMap = messages.chatMessageDepthFromEndMap()
        return messages.mapIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                val messageDepth = depthMap[index]
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (
                            part is UIMessagePart.Text &&
                            shouldParseThinkTagAfterVisualRegex(
                                text = part.text,
                                assistant = ctx.assistant,
                                messageDepthFromEnd = messageDepth,
                                enableThinkRegex = ctx.settings.displaySetting.enableThinkRegex
                            )
                        ) {
                            val stripped = part.text.replace(THINKING_REGEX, "")
                            val reasoning =
                                THINKING_REGEX.find(part.text)?.groupValues?.getOrNull(1)?.trim()
                                    ?: ""
                            val hasClosingTag = CLOSING_TAG_REGEX.containsMatchIn(part.text)
                            listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = reasoning,
                                    createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                                    finishedAt = if (hasClosingTag) Clock.System.now() else null,
                                ),
                                part.copy(text = stripped),
                            )
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = Clock.System.now()
        val depthMap = messages.chatMessageDepthFromEndMap()
        return messages.mapIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                val messageDepth = depthMap[index]
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (
                            part is UIMessagePart.Text &&
                            shouldParseThinkTagAfterVisualRegex(
                                text = part.text,
                                assistant = ctx.assistant,
                                messageDepthFromEnd = messageDepth,
                                enableThinkRegex = ctx.settings.displaySetting.enableThinkRegex
                            )
                        ) {
                            val stripped = part.text.replace(THINKING_REGEX, "")
                            val reasoning =
                                THINKING_REGEX.find(part.text)?.groupValues?.getOrNull(1)?.trim()
                                    ?: ""
                            listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = reasoning,
                                    createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                                    finishedAt = now,
                                ),
                                part.copy(text = stripped),
                            )
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }
}
