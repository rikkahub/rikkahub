package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

/**
 * Argument-validation helpers for the mcp_* tools. Returns either [Ok] with a typed
 * payload, or [Reject] with a stable error code + human-readable detail. The error code
 * is what the LLM matches on; the detail is what the user sees in the approval prompt or
 * the LLM uses to pick a recovery path.
 *
 * Kept separate from McpControlTools so each tool's argument parser stays small and the
 * test surface stays focused.
 */
object McpControlValidation {

    const val MAX_NAME_LENGTH = 60
    const val MAX_HEADERS = 32

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Reject(val error: String, val detail: String) : Result<Nothing>()
    }

    /**
     * Reject names that are blank, oversize, or duplicate an existing server's name
     * (case-insensitive). When [excludingId] is non-null, that server's own current name
     * is permitted — used by mcp_update so renaming to the same name doesn't false-trip.
     */
    fun validateName(
        candidate: String,
        existingServers: List<McpServerConfig>,
        excludingId: String? = null,
    ): Result<String> {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            return Result.Reject("invalid_name", "name is required and may not be blank")
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            return Result.Reject(
                "invalid_name",
                "name exceeds $MAX_NAME_LENGTH chars (got ${trimmed.length})"
            )
        }
        val lowered = trimmed.lowercase()
        val collision = existingServers.firstOrNull { srv ->
            srv.commonOptions.name.trim().lowercase() == lowered &&
                srv.id.toString() != excludingId
        }
        if (collision != null) {
            return Result.Reject(
                "name_already_in_use",
                "name '${collision.commonOptions.name}' is already used by server id ${collision.id}"
            )
        }
        return Result.Ok(trimmed)
    }

    /**
     * Reject header lists that exceed the soft cap, contain CR/LF (header-injection guard),
     * or have empty / non-token-char header names. Header VALUES are not scanned beyond
     * CR/LF — the bytes matter and over-sanitising would break legitimate auth schemes.
     */
    fun validateHeaders(headers: List<Pair<String, String>>): Result<List<Pair<String, String>>> {
        if (headers.size > MAX_HEADERS) {
            return Result.Reject(
                "too_many_headers",
                "header list exceeds $MAX_HEADERS entries (got ${headers.size})"
            )
        }
        for ((name, value) in headers) {
            if (name.isBlank()) {
                return Result.Reject("invalid_header_name", "header name may not be blank")
            }
            if (!isValidHttpToken(name)) {
                return Result.Reject(
                    "invalid_header_name",
                    "header name '$name' contains characters disallowed by RFC 7230 token rules"
                )
            }
            if (name.contains('\r') || name.contains('\n')) {
                return Result.Reject(
                    "invalid_header_name",
                    "header name '$name' contains CR or LF — header-injection blocked"
                )
            }
            if (value.contains('\r') || value.contains('\n')) {
                return Result.Reject(
                    "invalid_header_value",
                    "value for header '$name' contains CR or LF — header-injection blocked"
                )
            }
        }
        return Result.Ok(headers)
    }

    /**
     * RFC 7230 token chars: !#$%&'*+-.^_`|~ plus ALPHA and DIGIT. We're permissive here —
     * the goal is to reject obvious injection attempts like " " or ":" in header names,
     * not to be a strict RFC linter.
     */
    private fun isValidHttpToken(name: String): Boolean {
        if (name.isEmpty()) return false
        for (ch in name) {
            val isLetter = ch in 'a'..'z' || ch in 'A'..'Z'
            val isDigit = ch in '0'..'9'
            val isToken = ch in "!#$%&'*+-.^_`|~"
            if (!(isLetter || isDigit || isToken)) return false
        }
        return true
    }
}
