package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Pure, Android-free JWT-expiry logic for the paste-only ChatGPT access token.
//
// The ChatGPT (Codex) provider has no refresh flow: the user pastes a JWT and it eventually
// expires. The invariant enforced here is "never send a token we can PROVE is expired, and surface
// a clear message instead of a raw 401". Kept pure (no OkHttp, no android.*) so the regression test
// runs on the JVM in CI with no device/network.

/**
 * Decode the `exp` claim (Unix seconds) from a JWT's payload segment.
 *
 * Returns null on ANY non-JWT / malformed / claim-less input rather than throwing: a non-JWT paste
 * must fall through to the server's own auth error, never crash the request path.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun parseJwtExp(token: String): Long? {
    val segments = token.split(".")
    if (segments.size != 3) return null
    return runCatching {
        // JWT payloads are base64url WITHOUT padding; Base64.UrlSafe tolerates missing padding here.
        val payloadBytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            .decode(segments[1])
        val payload = Json.parseToJsonElement(payloadBytes.decodeToString()).jsonObject
        payload["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
}

/**
 * Whether a pasted token is provably expired at [nowEpochSeconds].
 *
 * Conservative: returns true ONLY when an `exp` claim is present AND now has reached it. A token
 * with no parseable `exp` (non-JWT, opaque token, malformed) is treated as not-expired so we never
 * block a token we cannot prove is expired — the server stays the source of truth for those.
 */
internal fun isChatGptTokenExpired(token: String, nowEpochSeconds: Long): Boolean {
    val exp = parseJwtExp(token) ?: return false
    return nowEpochSeconds >= exp
}
