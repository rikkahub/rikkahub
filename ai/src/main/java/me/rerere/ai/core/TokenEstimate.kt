package me.rerere.ai.core

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.math.ceil

// Conservative chars-per-token divisor for text. Real tokenizers average ~4 chars/token for English;
// this is only used for the small tail not yet covered by a real server-seen usage reading.
private const val CHARS_PER_TOKEN = 4.0

// JSON-ish content (tool input/args) packs denser per token than prose; ~2 chars/token is the
// conservative figure used by mature context managers (claude-code tokenEstimation).
private const val JSON_CHARS_PER_TOKEN = 2.0

// Flat per-part estimate for binary modalities (image/video/audio/document). Char-counting a base64
// data URL is catastrophically wrong: a 1 MB inline image would estimate ~325k tokens and spuriously
// fire destructive auto-compact. A flat figure matches how providers price a media part.
// (claude-code IMAGE_MAX_TOKEN_SIZE = 2000.)
internal const val MEDIA_PART_TOKEN_ESTIMATE = 2000

// Deliberate over-estimate multiplier on char-derived text so the trigger compacts a touch EARLY
// rather than letting a provider return "prompt too long". Applied only to char-based parts; flat
// media parts are already a fixed conservative figure.
private const val TEXT_FUDGE = 1.5

/**
 * Conservative token estimate for a single message part. Pure; no tokenizer dependency.
 *
 * Per-modality (design #193 R1 / refinement #1):
 * - Text / Reasoning: `chars / 4`, then the over-estimate fudge.
 * - Tool: `(name + input) / 2` (JSON packs denser), then fudge — covers a not-yet-executed call.
 *   Tool output parts are estimated recursively (they are themselves [UIMessagePart]s).
 * - Image / Video / Audio / Document: flat [MEDIA_PART_TOKEN_ESTIMATE] (never char-count base64).
 *
 * Always `>= 0`. Non-empty text/tool input yields `> 0`. Monotone under content append.
 */
@Suppress("DEPRECATION") // legacy ToolCall/ToolResult/Search arms required only for when-exhaustiveness
fun estimateTokens(part: UIMessagePart): Int = when (part) {
    is UIMessagePart.Text -> fudgedChars(part.text.length, CHARS_PER_TOKEN)
    is UIMessagePart.Reasoning -> fudgedChars(part.reasoning.length, CHARS_PER_TOKEN)
    is UIMessagePart.Tool -> {
        val callChars = part.toolName.length + part.input.length
        fudgedChars(callChars, JSON_CHARS_PER_TOKEN) + estimateTokens(part.output)
    }

    is UIMessagePart.ToolCall -> {
        val callChars = part.toolName.length + part.arguments.length
        fudgedChars(callChars, JSON_CHARS_PER_TOKEN)
    }

    is UIMessagePart.ToolResult -> fudgedChars(part.content.toString().length, JSON_CHARS_PER_TOKEN)
    is UIMessagePart.Image -> MEDIA_PART_TOKEN_ESTIMATE
    is UIMessagePart.Video -> MEDIA_PART_TOKEN_ESTIMATE
    is UIMessagePart.Audio -> MEDIA_PART_TOKEN_ESTIMATE
    is UIMessagePart.Document -> MEDIA_PART_TOKEN_ESTIMATE
    UIMessagePart.Search -> 0
}

/** Sum of [estimateTokens] over all parts. `>= 0`, monotone in the part list. */
fun estimateTokens(parts: List<UIMessagePart>): Int = parts.sumOf { estimateTokens(it) }

/** Sum of [estimateTokens] over every part of every message. `>= 0`, monotone in the message list. */
fun estimateTokensForMessages(messages: List<UIMessage>): Int =
    messages.sumOf { estimateTokens(it.parts) }

private fun fudgedChars(chars: Int, charsPerToken: Double): Int =
    if (chars <= 0) 0 else ceil(chars / charsPerToken * TEXT_FUDGE).toInt()

/**
 * Single canonical context-footprint measurement (design #193 R3), consumed by BOTH the auto-compact
 * trigger and the size warning so they can never disagree. Pure; depends only on [messages] (+ their
 * persisted usage).
 *
 * The real, provider-unified, server-seen token count lives on the LAST usage-bearing message's
 * [TokenUsage.totalTokens] (merged in-place during streaming). That is the running footprint of the
 * request that already happened. To it we add a conservative [estimateTokens] of everything appended
 * AFTER that anchor — the pending user turn and any just-added attachments/tool results — so a huge
 * single paste can't escape the trigger by being one turn ahead of the real count.
 *
 * The anchor keys on a REAL reading (`totalTokens > 0`), not mere `usage != null`: an all-zero
 * [TokenUsage] (0,0,0,0) is reachable and persisted — an OpenAI-compatible chunk carrying `"usage": {}`
 * or a cancelled/interrupted stream parses to a non-null zero usage (ChatCompletionsAPI.parseTokenUsage
 * returns null ONLY when the whole `usage` object is absent), and a new assistant turn whose only seen
 * usage is zero merges to a non-null TokenUsage(0,0,0,0). Anchoring on nullability would pick that
 * later zero as the anchor (anchorTotal = 0) and SHADOW an earlier turn's real total (e.g. 50k),
 * collapsing the footprint to ~0 and silently disabling the trigger/warning. A zero total carries no
 * signal, so it is transparent to the anchor: we fall back to the last turn that actually has one.
 *
 * Cold start (no message has a real usage yet, e.g. first turn or a cancelled stream that omitted
 * usage): estimate the whole list. If that is also empty, the result is 0 -> the caller treats it as
 * "no decision yet" (the no-op guard).
 */
fun contextTokens(messages: List<UIMessage>): Int {
    val anchorIndex = messages.indexOfLast { (it.usage?.totalTokens ?: 0) > 0 }
    if (anchorIndex < 0) {
        return estimateTokensForMessages(messages)
    }
    val anchorTotal = messages[anchorIndex].usage?.totalTokens ?: 0
    val tail = if (anchorIndex + 1 <= messages.lastIndex) {
        messages.subList(anchorIndex + 1, messages.size)
    } else {
        emptyList()
    }
    return anchorTotal + estimateTokensForMessages(tail)
}
