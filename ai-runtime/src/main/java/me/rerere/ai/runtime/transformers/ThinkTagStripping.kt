package me.rerere.ai.runtime.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Instant

private const val THINK_TAG_OPEN = "<think>"
private const val THINK_TAG_CLOSE = "</think>"
private const val THINK_TAG_OPEN_LENGTH = 7
private const val THINK_TAG_CLOSE_LENGTH = 8

private data class LeadingThinkBlockScanResult(
    val reasoningBlocks: List<String>,
    val visibleText: String,
)

private fun splitLeadingThinkBlocks(text: String): LeadingThinkBlockScanResult {
    var index = 0
    while (index < text.length && text[index].isWhitespace()) {
        index += 1
    }
    if (index >= text.length) {
        return LeadingThinkBlockScanResult(emptyList(), text)
    }
    if (!text.regionMatches(index, THINK_TAG_OPEN, 0, THINK_TAG_OPEN_LENGTH, ignoreCase = false)) {
        return LeadingThinkBlockScanResult(emptyList(), text)
    }

    val leadingWhitespace = text.substring(0, index)
    val reasoningBlocks = mutableListOf<String>()
    var pointer = index
    var lastClosedBlockEnd = -1

    while (pointer < text.length) {
        if (!text.regionMatches(pointer, THINK_TAG_OPEN, 0, THINK_TAG_OPEN_LENGTH, ignoreCase = false)) {
            break
        }

        val closeTagStart = text.indexOf(THINK_TAG_CLOSE, pointer + THINK_TAG_OPEN_LENGTH)
        if (closeTagStart < 0) {
            return LeadingThinkBlockScanResult(
                reasoningBlocks = reasoningBlocks,
                visibleText = if (reasoningBlocks.isNotEmpty()) {
                    leadingWhitespace + text.substring(lastClosedBlockEnd)
                } else {
                    text
                },
            )
        }

        reasoningBlocks.add(
            text.substring(pointer + THINK_TAG_OPEN_LENGTH, closeTagStart).trim(),
        )
        lastClosedBlockEnd = closeTagStart + THINK_TAG_CLOSE_LENGTH
        pointer = lastClosedBlockEnd
        while (pointer < text.length && text[pointer].isWhitespace()) {
            pointer += 1
        }
        if (pointer < text.length &&
            text.regionMatches(pointer, THINK_TAG_OPEN, 0, THINK_TAG_OPEN_LENGTH, ignoreCase = false)
        ) {
            // Still in the leading preamble; continue scanning next block.
            continue
        }

        return LeadingThinkBlockScanResult(
            reasoningBlocks = reasoningBlocks,
            visibleText = leadingWhitespace + text.substring(lastClosedBlockEnd),
        )
    }

    return if (reasoningBlocks.isNotEmpty()) {
        LeadingThinkBlockScanResult(
            reasoningBlocks = reasoningBlocks,
            visibleText = leadingWhitespace,
        )
    } else {
        LeadingThinkBlockScanResult(emptyList(), text)
    }
}

/**
 * Provider-agnostic `<think>…</think>` extraction (issue #260): some providers stream the reasoning
 * trace inline in the assistant text instead of emitting [UIMessagePart.Reasoning] parts. This pulls
 * leading think blocks out of each assistant [UIMessagePart.Text] into ordered [UIMessagePart.Reasoning]
 * parts and strips only those tags from the remaining text. Leading extraction stops once visible
 * answer text begins, and only tags in the leading preamble are removed.
 *
 * The wall clock is INJECTED ([now]/[zone]) rather than read here, so the function carries no
 * `Clock.System` dependency and stays inside the `:ai-runtime` boundary (P3) and is deterministically
 * unit-testable. The app `ThinkTagTransformer` binds [now]/[zone] from the system clock.
 *
 * Unclosed leading `<think>` blocks are never extracted and remain literal visible text.
 */
fun stripThinkTags(
    messages: List<UIMessage>,
    now: Instant,
    zone: TimeZone,
): List<UIMessage> = messages.map { message ->
    if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
        message.copy(
            parts = message.parts.flatMap { part ->
                if (part is UIMessagePart.Text) {
                    val parsed = splitLeadingThinkBlocks(part.text)
                    if (parsed.reasoningBlocks.isEmpty()) {
                        listOf(part)
                    } else {
                        val createdAt = message.createdAt.toInstant(timeZone = zone)
                        val reasoningParts = parsed.reasoningBlocks.map { reasoning ->
                            UIMessagePart.Reasoning(
                                reasoning = reasoning,
                                createdAt = createdAt,
                                finishedAt = now,
                            )
                        }
                        reasoningParts + part.copy(text = parsed.visibleText)
                    }
                } else {
                    listOf(part)
                }
            }
        )
    } else {
        message
    }
}
