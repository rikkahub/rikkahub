package me.rerere.rikkahub.data.ai.mcp.control

/**
 * Display-layer redactor for sensitive MCP header values.
 *
 * The header values themselves are persisted verbatim via PreferencesStore — we need the
 * real bytes to actually authenticate against the MCP server. This object is consulted only
 * by surfaces that render headers to the user / LLM / logcat: the approval prompt, the
 * `mcp_get` and `mcp_list` tool results, the Telegram inline-keyboard prompt, and any
 * future debug log of the active MCP config.
 *
 * Matching is case-insensitive on the header name (HTTP header names are case-insensitive
 * per RFC 7230 §3.2). Values shorter than 4 chars are redacted to "…" (no last-4 dance —
 * showing the entire value would defeat the redaction).
 *
 * If a sensitive header you care about isn't matched here, ADD IT — the cost of one extra
 * entry is zero, the cost of leaking a token in a Telegram approval prompt is a refund
 * event.
 */
object McpHeaderRedactor {

    /**
     * Header names whose values must be redacted before display. Compared case-insensitively.
     * Add entries when you encounter new auth schemes in the wild.
     */
    private val SENSITIVE_HEADER_NAMES: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "x-api-token",
        "x-auth-token",
        "x-access-token",
        "cookie",
        "set-cookie",
        "x-csrf-token",
    )

    fun isSensitive(headerName: String): Boolean =
        headerName.trim().lowercase() in SENSITIVE_HEADER_NAMES

    /**
     * Redact the value of one header. For sensitive names, returns `…<last4>` if the value
     * is long enough; otherwise just `…`. Pure function, no logging side effects. Plain
     * (non-sensitive) headers pass through unchanged so the LLM can see e.g. `Content-Type:
     * application/json` plainly.
     */
    fun redactHeaderValue(name: String, value: String): String {
        if (!isSensitive(name)) return value
        return if (value.length >= 4) "…${value.takeLast(4)}" else "…"
    }

    /** Bulk-redact a list of (name, value) pairs preserving order and duplicates. */
    fun redactHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.map { (name, value) -> name to redactHeaderValue(name, value) }

    /**
     * Counts of (sensitive, plain) headers in the input list — used by the approval prompt
     * to render the trailing "(2 secret headers, 1 plain)" summary.
     */
    fun classify(headers: List<Pair<String, String>>): Pair<Int, Int> {
        var sensitive = 0
        var plain = 0
        for ((name, _) in headers) {
            if (isSensitive(name)) sensitive++ else plain++
        }
        return sensitive to plain
    }
}
