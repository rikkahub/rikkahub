package me.rerere.locallm.litert

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool

private const val TAG = "LiteRtToolPrefix"

/**
 * Prompt-engineered tool calling for LiteRT models. The model is taught the
 * <tool_call>{json}</tool_call> shape via a system-prompt prefix, the streaming
 * response is scanned for blocks, and each block is parsed with lenient JSON
 * (handles trailing commas + whitespace).
 *
 * Mirrors the path AICoreProvider already uses for Gemini Nano.
 */
object LiteRtToolPrefix {

    /** Lenient JSON: trailing commas are ignored, unknown keys preserved. */
    private val lenient = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    /**
     * Adaptive tool-prefix budget. Scales with the model's `maxNumTokens` so large-context
     * models (Gemma 4 = 32k) get every tool while small-context models (Qwen 1.5B = 4k)
     * keep a tight cap. Three tiers:
     *
     *   - small (`maxNumTokens < 6144`): the original 25 tools / 2000 chars cap. Stays safe
     *     on Qwen2.5-1.5B-Instruct (4k context) and Gemma3-1B-IT (1k effective).
     *   - medium (`6144 <= maxNumTokens < 16384`): 60 tools / 4500 chars. A future
     *     mid-sized local model would land here.
     *   - large (`>= 16384`): unlimited tools, 12000 chars of total budget. Gemma 4 E2B
     *     and E4B have 32k context and fit every enabled tool here with room to spare.
     *
     * The 12000-char ceiling is a defensive cap to keep the prompt under ~3000 tokens
     * — even at 32k context a runaway 50-kB tool list would crowd out the user's
     * conversation history.
     */
    data class Budget(val maxTools: Int, val maxChars: Int)

    fun budgetForContext(maxNumTokens: Int): Budget = when {
        maxNumTokens >= 16384 -> Budget(maxTools = Int.MAX_VALUE, maxChars = 12000)
        maxNumTokens >= 6144 -> Budget(maxTools = 60, maxChars = 4500)
        else -> Budget(maxTools = 25, maxChars = 2000)
    }

    // Capture everything between <tool_call> and </tool_call> and hand the raw text to
    // the lenient JSON parser.  Using a greedy [\s\S]* here is intentional: the closing
    // tag "</tool_call>" is the real terminator, so there's no risk of run-on matching.
    // An earlier non-greedy \{[\s\S]*?\} would stop at the first "}" — breaking nested
    // argument objects like {"key": {"nested": 1}}.
    // RegexOption.MULTILINE only affects ^ and $ anchors which this pattern doesn't use,
    // so it's been removed to avoid confusion.
    private val toolCallPattern = Regex(
        pattern = "<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>",
    )

    data class ParsedCall(val name: String, val arguments: JsonObject)

    /**
     * Returns the system-prompt prefix that teaches the model how to invoke tools.
     * Empty string when [tools] is empty.
     *
     * Full variant — dumps every tool's complete JSON-schema. Used by cloud models
     * where context windows are 32k–200k+ and a few thousand tokens of schema are
     * cheap. Do NOT use this for local LiteRT models on 4k-context devices —
     * see [buildCompactPrefix] instead.
     */
    fun buildPrefix(tools: List<Tool>): String {
        if (tools.isEmpty()) return ""
        val toolList = tools.joinToString("\n") { tool ->
            val schema = runCatching { tool.parameters().toString() }.getOrDefault("{}")
            "- ${tool.name}: ${tool.description}\n  arguments schema: $schema"
        }
        return """
You have access to the following tools. To call one, emit a single line in this exact format:
<tool_call>{"name": "<tool_name>", "arguments": {<json arguments>}}</tool_call>

Available tools:
$toolList

Only emit tool calls when actually needed. Otherwise reply with normal text.

""".trimStart()
    }

    /**
     * Compact variant for small-context models (Qwen2.5-1.5B-Instruct = 4k tokens,
     * Gemma3-1B-IT = 1k, etc.). Emits one line per tool — `- name: description` —
     * with the description capped at 100 chars and JSON schemas omitted entirely.
     * The model is told to call tools by name with sensible JSON arguments; for tiny
     * models that can't follow strict schemas anyway, the schema dump just burns
     * thousands of tokens with no quality benefit.
     *
     * Repro that motivated this: a fresh "hi" against a freshly-installed Qwen
     * produced "Input token ids are too long: 18031 >= 4096" because the full
     * [buildPrefix] inlined every tool's schema (50+ tools × ~300 chars schema each).
     */
    fun buildCompactPrefix(
        tools: List<Tool>,
        maxTools: Int = Int.MAX_VALUE,
        maxChars: Int = Int.MAX_VALUE,
    ): String {
        if (tools.isEmpty()) return ""
        // Take the first `maxTools` and stop early once the running prefix length
        // would exceed `maxChars`. Tail tools dropped get summarised in a single
        // line ("…and N more — describe them when asked") so the model knows the
        // surface is bigger than what it sees.
        val capped = tools.take(maxTools)
        val sb = StringBuilder()
        var renderedCount = 0
        for (tool in capped) {
            val firstLineDesc = tool.description.lineSequence().firstOrNull()?.trim().orEmpty()
            val line = "- ${tool.name}: ${firstLineDesc.take(80)}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
            renderedCount++
        }
        val droppedTotal = tools.size - renderedCount
        val droppedNote = if (droppedTotal > 0) {
            "(…and $droppedTotal more tool(s) hidden to keep prompt short — disable some, switch to a model with bigger context, or raise \"Max context\" to see all)\n"
        } else ""
        // Note the explicit "include the closing </tool_call> tag": tiny models otherwise
        // stop right after the JSON `}}`. extractToolCalls recovers an unclosed call
        // anyway, but nudging the model to close the tag keeps the happy path clean.
        return """
Tools you can call. To call one, output exactly: <tool_call>{"name":"<n>","arguments":{<json>}}</tool_call> — and include the closing </tool_call> tag. Only call a tool when actually needed; otherwise reply normally.

${sb}${droppedNote}
""".trimStart()
    }

    /**
     * Scan [response] for tool calls and return the parsed calls. Returns an empty list
     * when nothing matches or every match fails to parse.
     *
     * Two passes:
     *  1. Well-formed `<tool_call>…</tool_call>` blocks (the happy path).
     *  2. **Unclosed-tag recovery.** Tiny local models (Qwen2.5-1.5B, Gemma3-1B) very
     *     frequently emit `<tool_call>{json}` and then stop WITHOUT the closing
     *     `</tool_call>` tag — the compact prompt even tells them to "stop" right after
     *     the JSON. Pass 1's regex requires the closing tag, so without this fallback the
     *     whole tool call leaks to the user as raw text and never fires. When pass 1 finds
     *     nothing but a `<tool_call>` opener exists, we parse the first balanced JSON
     *     object after the LAST opener instead.
     */
    fun extractToolCalls(response: String): List<ParsedCall> {
        // Pass 1 — well-formed blocks.
        val closed = toolCallPattern.findAll(response)
            .mapNotNull { parseToolCallJson(it.groupValues[1]) }
            .toList()
        if (closed.isNotEmpty()) return closed

        // Pass 2 — unclosed-tag recovery.
        val openerIdx = response.lastIndexOf("<tool_call>")
        if (openerIdx >= 0) {
            val afterOpener = response.substring(openerIdx + "<tool_call>".length)
            val json = firstBalancedJsonObject(afterOpener)
            if (json != null) {
                parseToolCallJson(json)?.let { return listOf(it) }
            }
        }
        return emptyList()
    }

    /** Parse one tool-call JSON payload into a [ParsedCall], or null if it is not a valid
     *  call object. Lenient about trailing commas + whitespace. */
    private fun parseToolCallJson(raw: String): ParsedCall? = runCatching {
        val obj = lenient.parseToJsonElement(raw).jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val args = (obj["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        ParsedCall(name, args)
    }.onFailure { t ->
        // Log parse failures so model quality issues are diagnosable in logcat.
        // Silent drops are fine UX (the model produces text output instead), but the log
        // distinguishes "model didn't call a tool" from "model emitted malformed JSON".
        Log.w(TAG, "Failed to parse tool_call block — raw=[$raw]: ${t.message}")
    }.getOrNull()

    /**
     * Return the first brace-balanced `{…}` JSON object found in [text], or null if there
     * is no `{` or the object is never closed (model truncated mid-JSON). String contents
     * are skipped so a `}` inside a JSON string value does not close the object early.
     */
    private fun firstBalancedJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Returns [response] with every <tool_call>...</tool_call> block removed.
     */
    fun stripToolCallBlocks(response: String): String =
        toolCallPattern.replace(response, "").trim().let {
            it.replace(Regex("\\s{3,}"), "  ")
        }
}
