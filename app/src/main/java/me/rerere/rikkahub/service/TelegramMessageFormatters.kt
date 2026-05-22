package me.rerere.rikkahub.service

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Pure presentation helpers shared by TelegramBotService when rendering assistant messages,
 * approval cards, and live-edit footers to Telegram. All functions are state-free — the only
 * input is the message / numeric value being formatted.
 *
 * `tokenUsageFooter` is the one exception: it takes the user's `showTokenUsage` display
 * preference as an explicit parameter instead of reading SettingsStore, so the formatter
 * stays pure and the service is the only thing that touches preferences.
 */

internal fun assistantToolSummary(m: UIMessage): String {
    val tools = m.parts.filterIsInstance<UIMessagePart.Tool>()
    if (tools.isEmpty()) return ""
    return buildString {
        append("🔧 Tools used:\n")
        tools.forEachIndexed { idx, t ->
            val outText = t.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
            val (icon, hint) = classifyToolOutput(t.isExecuted, outText)
            val isLast = idx == tools.lastIndex
            if (!isLast) {
                // Earlier tool: compact one-liner.
                append(icon).append(' ').append(t.toolName)
                if (hint.isNotEmpty()) append(" — ").append(hint)
                append('\n')
            } else {
                // Latest tool: expanded view with args + truncated output.
                append(icon).append(' ').append(t.toolName)
                if (hint.isNotEmpty()) append(" — ").append(hint)
                append('\n')
                val argsBlock = formatArgsForDisplay(t.input)
                if (argsBlock.isNotEmpty()) {
                    append("```\nin: ").append(argsBlock).append("\n```\n")
                }
                val outBlock = formatOutputForDisplay(outText, executed = t.isExecuted)
                if (outBlock.isNotEmpty()) {
                    append("```\nout: ").append(outBlock).append("\n```")
                }
            }
        }
    }.trimEnd()
}

/**
 * Trim a tool's input JSON for display. Empty / "{}" args render as nothing so we
 * don't waste a code-block on a noise line. Anything longer than 200 chars gets
 * tail-elided.
 */
internal fun formatArgsForDisplay(rawInput: String): String {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "null") return ""
    val limit = 200
    return if (trimmed.length > limit) trimmed.substring(0, limit) + "…" else trimmed
}

/**
 * Trim a tool's output for display. Returns "running…" while the tool is still in
 * flight (no output yet). Truncates to ~300 chars; long stdout / large JSON blobs
 * are surface-rendered, not full-rendered.
 */
internal fun formatOutputForDisplay(outText: String, executed: Boolean): String {
    if (!executed) return "running…"
    val trimmed = outText.trim()
    if (trimmed.isEmpty()) return ""
    val limit = 300
    return if (trimmed.length > limit) trimmed.substring(0, limit) + "…" else trimmed
}

/**
 * Token-usage footer for the final reply. Mirrors the in-app ChatMessageNerdLine:
 * input tokens (with cached annotation if any), output tokens, tok/s, wall-clock.
 * Returns empty string when usage is missing or [showTokenUsage] is false.
 */
internal fun tokenUsageFooter(m: UIMessage, showTokenUsage: Boolean): String {
    val usage = m.usage ?: return ""
    if (!showTokenUsage) return ""
    val parts = mutableListOf<String>()
    val input = if (usage.cachedTokens > 0) {
        "${compactNumber(usage.promptTokens)}↑ (${compactNumber(usage.cachedTokens)} cached)"
    } else {
        "${compactNumber(usage.promptTokens)}↑"
    }
    parts.add(input)
    parts.add("${compactNumber(usage.completionTokens)}↓")
    // tok/s + duration: only when both timestamps and a positive duration exist.
    val finishedAt = m.finishedAt
    val createdAt = m.createdAt
    if (finishedAt != null) {
        val zone = TimeZone.currentSystemDefault()
        val durMs = finishedAt.toInstant(zone).toEpochMilliseconds() -
            createdAt.toInstant(zone).toEpochMilliseconds()
        if (durMs > 0 && usage.completionTokens > 0) {
            val tps = usage.completionTokens.toDouble() / durMs.toDouble() * 1000.0
            parts.add(String.format(java.util.Locale.US, "%.1f tok/s", tps))
        }
        if (durMs > 0) {
            parts.add(formatDurationCompact(durMs))
        }
    }
    return "📊 " + parts.joinToString(" · ")
}

/** 1234 → "1.2K", 12_345_678 → "12.3M". Below 1000 returns the raw number. */
internal fun compactNumber(n: Int): String {
    if (n < 1_000) return n.toString()
    if (n < 1_000_000) return String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
    return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
}

/** 1234 → "1.2s", 65_432 → "1m05s", 3_725_000 → "1h02m". */
internal fun formatDurationCompact(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 60 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
        totalSec < 3600 -> String.format(java.util.Locale.US, "%dm%02ds", totalSec / 60, totalSec % 60)
        else -> String.format(java.util.Locale.US, "%dh%02dm", totalSec / 3600, (totalSec % 3600) / 60)
    }
}

/**
 * Drops the noisy "/data/data/com.termux/files/usr/bin/bash: line N: " prefix that
 * Termux's bash adds to every stderr line. Without this every shell error reads:
 *   "/data/data/com.termux/files/usr/bin/bash: line 1: npm: command not found"
 * which buries the actual signal ("npm: command not found"). Best-effort regex; if no
 * match, returns the line unchanged.
 */
internal fun trimShellPrefix(line: String): String {
    val rx = Regex("""^(?:/[^:]*?bash|sh|/bin/[a-z]+):\s*line\s+\d+:\s*""")
    return rx.replaceFirst(line, "")
}

/**
 * Picks a status icon + one-line hint for a single tool result. Reads only well-known
 * envelope keys (success / error / exit_code / count / reason / file_path) so the
 * summary stays consistent across tools. Returns ("🔄", "running") for in-flight calls.
 */
internal fun classifyToolOutput(executed: Boolean, raw: String): Pair<String, String> {
    if (!executed) return "🔄" to "running"
    if (raw.isBlank()) return "✅" to ""
    // The output is conventionally a single JSON object string. Best-effort parse;
    // if it's not JSON we fall back to a length-capped preview.
    val obj = runCatching {
        Json.parseToJsonElement(raw).jsonObject
    }.getOrNull()
    if (obj == null) {
        val preview = raw.take(80).replace("\n", " ").trim()
        return "✅" to preview
    }
    // Error envelope wins: error key OR success:false.
    val errorVal = obj["error"]?.jsonPrimitive?.contentOrNull
    if (!errorVal.isNullOrBlank()) {
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
        val tail = if (!reason.isNullOrBlank()) "$errorVal ($reason)" else errorVal
        return "❌" to tail.take(100)
    }
    val successPrim = obj["success"]?.jsonPrimitive?.contentOrNull
    val explicitFalse = successPrim == "false"
    // Exit-code based: shell tools surface a numeric exit_code. Non-zero is a soft fail.
    val exit = obj["exit_code"]?.jsonPrimitive?.intOrNull
    if (exit != null && exit != 0) {
        val stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.let { trimShellPrefix(it) }
            ?.take(80)
        return "⚠️" to ("exit $exit" + (if (!stderr.isNullOrBlank()) " · $stderr" else ""))
    }
    if (explicitFalse) {
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
        return "❌" to ("failed" + (if (!reason.isNullOrBlank()) " ($reason)" else ""))
    }
    // Success path: surface the most informative scalar we can find without dumping JSON.
    val count = obj["count"]?.jsonPrimitive?.intOrNull
        ?: obj["total_in_buffer"]?.jsonPrimitive?.intOrNull
        ?: (obj["jobs"] as? JsonArray)?.size
        ?: (obj["notifications"] as? JsonArray)?.size
        ?: (obj["matches"] as? JsonArray)?.size
        ?: (obj["apps"] as? JsonArray)?.size
        ?: (obj["nodes"] as? JsonArray)?.size
    val stdoutSnippet = obj["stdout"]?.jsonPrimitive?.contentOrNull
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        ?.let { trimShellPrefix(it) }
        ?.take(80)
    val filePath = obj["file_path"]?.jsonPrimitive?.contentOrNull
    val hint = when {
        count != null -> if (count == 1) "1 result" else "$count results"
        !stdoutSnippet.isNullOrBlank() -> stdoutSnippet
        !filePath.isNullOrBlank() -> "saved ${filePath.substringAfterLast('/')}"
        successPrim == "true" -> "ok"
        else -> ""
    }
    return "✅" to hint
}
