package me.rerere.ai.runtime.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Instant

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)

/**
 * Provider-agnostic `<think>…</think>` extraction (issue #260): some providers stream the reasoning
 * trace inline in the assistant text instead of emitting [UIMessagePart.Reasoning] parts. This pulls
 * every think block out of each assistant [UIMessagePart.Text] into ordered [UIMessagePart.Reasoning]
 * parts and strips the tags from the remaining text. Each block becomes its own Reasoning part (a
 * single `replace` + `find` collapses multiple blocks into one and drops the rest).
 *
 * The wall clock is INJECTED ([now]/[zone]) rather than read here, so the function carries no
 * `Clock.System` dependency and stays inside the `:ai-runtime` boundary (P3) and is deterministically
 * unit-testable. The app `ThinkTagTransformer` binds [now]/[zone] from the system clock.
 *
 * @param finishUnclosed when true, an unclosed trailing `<think>` (no `</think>`) is also stamped
 *   [now] as finished — the generation-complete semantics; when false, only blocks with a closing tag
 *   are marked finished and an in-flight block stays `finishedAt = null` — the streaming semantics.
 */
fun stripThinkTags(
    messages: List<UIMessage>,
    now: Instant,
    zone: TimeZone,
    finishUnclosed: Boolean,
): List<UIMessage> = messages.map { message ->
    if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
        message.copy(
            parts = message.parts.flatMap { part ->
                if (part is UIMessagePart.Text && THINKING_REGEX.containsMatchIn(part.text)) {
                    val stripped = part.text.replace(THINKING_REGEX, "")
                    val createdAt = message.createdAt.toInstant(timeZone = zone)
                    val reasoningParts = THINKING_REGEX.findAll(part.text).map { match ->
                        val hasClosingTag = match.value.endsWith("</think>")
                        UIMessagePart.Reasoning(
                            reasoning = match.groupValues.getOrNull(1)?.trim() ?: "",
                            createdAt = createdAt,
                            finishedAt = if (hasClosingTag || finishUnclosed) now else null,
                        )
                    }.toList()
                    reasoningParts + part.copy(text = stripped)
                } else {
                    listOf(part)
                }
            }
        )
    } else {
        message
    }
}
