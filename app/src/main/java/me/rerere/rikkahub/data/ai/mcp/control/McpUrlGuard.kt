package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import java.net.URI

/**
 * Validates MCP server URLs before they get added to the user's persistent config.
 *
 * Two layers of protection beyond what HARDLINE catches:
 *   1. **Scheme guard.** Only `http://` and `https://` accepted. `file://`, `data:`,
 *      `javascript:`, `ftp://`, etc. all rejected — preventing the LLM from adding a server
 *      whose "URL" is actually a path-traversal bait or a side-channel.
 *   2. **Loopback in headless context.** When a tool call is dispatched from a scheduled
 *      job / workflow / sub-agent (anything in [HeadlessConversations]), reject loopback
 *      hosts (`127.0.0.0/8`, `::1`, the literal `localhost`). Rationale: the LLM running
 *      autonomously at 3am should not be able to silently wire itself to a localhost
 *      service it shouldn't talk to. In an interactive context (in-app chat or Telegram
 *      bot), the user is present — loopback is the EXPECTED workflow (Termux + mcp-proxy
 *      on the phone itself), so we allow it.
 *
 * Note on heuristic for "headless context": v1 uses [HeadlessConversations.activeIds] —
 * if any conversation is currently marked headless globally, treat the call as headless.
 * This has a small false-positive race when a cron job fires concurrently with an
 * interactive request (the interactive request gets the loopback rejection too), but it
 * fails CLOSED, never silently allowing a headless call. Phase 11 (sub-agents) and Phase 12
 * (workflows) will tighten this by threading the calling conversation id via a
 * CoroutineContext element so the check is per-call, not global.
 */
object McpUrlGuard {

    sealed class Result {
        data object Ok : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    /**
     * Validate a candidate MCP URL.
     *
     * @param url The URL string from the LLM tool argument.
     * @param headless True if the call is being dispatched in a headless (autonomous) context.
     *                 The MCP tool factory derives this from [HeadlessConversations.activeIds].
     */
    fun check(url: String, headless: Boolean): Result {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return Result.Reject("invalid_url", "url is empty")
        }
        val parsed = try {
            URI(trimmed)
        } catch (t: Throwable) {
            return Result.Reject("invalid_url", "could not parse url: ${t.message ?: t.javaClass.simpleName}")
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Reject(
                "unsupported_url_scheme",
                "only http and https are accepted; got '${scheme ?: "(none)"}'"
            )
        }
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) {
            return Result.Reject("invalid_url", "url is missing a host component")
        }
        if (headless && isLoopback(host)) {
            return Result.Reject(
                "loopback_in_headless_context",
                "loopback URLs ($host) require an interactive context; this call is headless"
            )
        }
        return Result.Ok
    }

    /**
     * True if [host] is a loopback address. Catches the three forms the LLM is likely to emit:
     *  - The literal string `localhost`
     *  - IPv4 loopback range `127.0.0.0/8`
     *  - IPv6 loopback `::1` (with or without zone id)
     *
     * We deliberately do NOT do DNS resolution here — a malicious DNS entry that points
     * `internal.attacker-controlled.com` at `127.0.0.1` would still be accepted in the
     * interactive context (where loopback is allowed) and rejected only in headless
     * (where the LLM shouldn't be calling it anyway). DNS-based attacks on the URL guard
     * are out of scope for v1.
     */
    fun isLoopback(host: String): Boolean {
        // URI.host returns IPv6 hosts with brackets on some JDKs (`[::1]`) and without on
        // others (`::1`) — strip both forms before comparing so we don't depend on which
        // platform we're on. Also strip a trailing dot — `localhost.` resolves identical
        // to `localhost` but the regex below wouldn't match it without normalisation.
        val normalized = host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "localhost") return true

        // For anything that LOOKS like an IP literal (only digits/dots, or contains a
        // colon), defer to InetAddress for canonical detection. Critically we only call
        // getByName on strings that are unambiguously IP literals — so no DNS resolution
        // happens. This catches the four documented audit-finding bypasses:
        //   - 0:0:0:0:0:0:0:1 (long-form IPv6 loopback)
        //   - ::ffff:127.0.0.1 (IPv4-mapped IPv6 loopback)
        //   - 0.0.0.0 (any-local — binds locally, treat as loopback for guard purposes)
        //   - 127.x.x.x in any decimal-octet form
        val looksLikeIpLiteral = normalized.contains(':') ||
            normalized.matches(Regex("""[0-9.]+"""))
        if (looksLikeIpLiteral) {
            val addr = runCatching { java.net.InetAddress.getByName(normalized) }.getOrNull()
            if (addr != null && (addr.isLoopbackAddress || addr.isAnyLocalAddress)) return true
        }
        return false
    }

    /** Convenience helper: ask the headless registry once and return the boolean. */
    fun currentlyHeadless(): Boolean = HeadlessConversations.activeIds().isNotEmpty()
}
