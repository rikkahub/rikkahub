package me.rerere.rikkahub.service

/**
 * The pure pre-flight DECISIONS of a chat turn (issue #360 P6), lifted out of the
 * `ChatService.handleMessageComplete` orchestration so the branchy bits — the sender-name fallback,
 * the tool-unavailable warning predicate (with its short-circuit), and the message-range slice (with
 * its `endInclusive + 1` boundary) — are JVM-unit-testable without the full ChatService.
 *
 * The turn runner itself stays in ChatService ON PURPOSE: it is irreducible orchestration that
 * depends on ~30 collaborators (session, automation lease, generation pipeline, streaming coalescer,
 * persistence, notifications, agent-event drain). Hoisting it into a separate class would just
 * relocate that god-object behind a 30-port constructor — the exact shape #360 exists to dissolve.
 * So P6 extracts the decision logic the runner CONSUMES, not the runner, leaving each helper at the
 * same position and with the same inputs it had inline.
 */

/**
 * The display name attributed to the assistant turn. When the assistant uses its own avatar, its
 * name is shown, falling back to [defaultAssistantName] only when that name is blank; otherwise the
 * model's display name is shown.
 *
 * [defaultAssistantName] is a lambda so the fallback string is resolved LAZILY — only when the avatar
 * is used AND the name is empty — exactly as the original inline `ifEmpty { strings.getString(...) }`.
 */
fun resolveSenderName(
    useAssistantAvatar: Boolean,
    assistantName: String,
    modelDisplayName: String,
    defaultAssistantName: () -> String,
): String =
    if (useAssistantAvatar) assistantName.ifEmpty(defaultAssistantName) else modelDisplayName

/**
 * Whether to surface the "tools unavailable" warning: the model lacks the TOOL ability yet the turn
 * would otherwise carry tools (web search on, or at least one MCP tool configured).
 *
 * [hasMcpTools] is a suspend probe invoked LAZILY behind the cheaper checks — only when the model
 * lacks tool support AND web search is off — preserving the original short-circuit
 * (`!supports && (webSearch || mcp())`), so the MCP enumeration is not run when the answer is already
 * decided.
 */
suspend fun shouldWarnToolUnavailable(
    modelSupportsTools: Boolean,
    webSearchEnabled: Boolean,
    hasMcpTools: suspend () -> Boolean,
): Boolean =
    !modelSupportsTools && (webSearchEnabled || hasMcpTools())

/**
 * The messages a turn actually generates over: the whole list, or the inclusive [range] slice when a
 * partial re-generation targets a sub-range. Mirrors the original inline `subList(start,
 * endInclusive + 1)` — a VIEW over [messages], not a copy, and the `+ 1` converts the inclusive end
 * to `subList`'s exclusive bound.
 */
fun <T> sliceTurnMessages(messages: List<T>, range: ClosedRange<Int>?): List<T> =
    if (range != null) messages.subList(range.start, range.endInclusive + 1) else messages
