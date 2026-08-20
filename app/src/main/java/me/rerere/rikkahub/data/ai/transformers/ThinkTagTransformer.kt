package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.time.Instant

private val THINKING_REGEX = Regex("\\A\\s*<think>([\\s\\S]*?)(</think>|$)")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = false,
        )
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = true,
        )
    }
}

internal fun List<UIMessage>.transformThinkTags(
    now: Instant,
    generationFinished: Boolean,
): List<UIMessage> = map { message ->
    if (message.role != MessageRole.ASSISTANT) {
        return@map message
    }
    if (message.hasPart<UIMessagePart.Reasoning>()) {
        return@map if (generationFinished) {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = now)
                    } else {
                        part
                    }
                }
            )
        } else {
            message
        }
    }

    val textPartIndex = message.parts.indexOfFirst { part ->
        part is UIMessagePart.Text && part.text.isNotBlank()
    }
    val textPart = message.parts.getOrNull(textPartIndex) as? UIMessagePart.Text
        ?: return@map message
    val match = THINKING_REGEX.find(textPart.text) ?: return@map message
    val hasClosingTag = match.groups[2]?.value == "</think>"
    val reasoning = UIMessagePart.Reasoning(
        reasoning = match.groupValues[1].trim(),
        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
        finishedAt = if (generationFinished || hasClosingTag) now else null,
    )
    val strippedText = textPart.copy(text = textPart.text.removeRange(match.range))

    message.copy(
        parts = buildList {
            addAll(message.parts.subList(0, textPartIndex))
            add(reasoning)
            add(strippedText)
            addAll(message.parts.subList(textPartIndex + 1, message.parts.size))
        }
    )
}
