package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import java.util.Base64

internal data class CodexIdentity(
    val accountId: String,
    val userId: String,
    val email: String,
    val name: String,
)

internal fun parseCodexIdentity(idToken: String, json: Json): CodexIdentity {
    val parts = idToken.split('.')
    require(parts.size == 3) { "Invalid ID token" }
    val payload = Base64.getUrlDecoder().decode(parts[1])
    val claims = json.parseToJsonElement(payload.decodeToString()).jsonObject
    val auth = claims["https://api.openai.com/auth"]?.jsonObject ?: JsonObject(emptyMap())
    val accountId = auth["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull
        ?: error("Missing ChatGPT account ID")
    return CodexIdentity(
        accountId = accountId,
        userId = auth["chatgpt_user_id"]?.jsonPrimitive?.contentOrNull
            ?: claims["sub"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        email = claims["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = claims["name"]?.jsonPrimitive?.contentOrNull
            ?: claims["email"]?.jsonPrimitive?.contentOrNull
            ?: "OpenAI",
    )
}

internal fun parseCodexUsage(jsonObject: JsonObject): CodexUsageSnapshot {
    val rateLimit = jsonObject["rate_limit"] as? JsonObject
    return CodexUsageSnapshot(
        primary = (rateLimit?.get("primary_window") as? JsonObject)?.toUsageWindow(),
        secondary = (rateLimit?.get("secondary_window") as? JsonObject)?.toUsageWindow(),
    )
}

internal fun parseCodexUsage(headers: Headers): CodexUsageSnapshot? {
    fun window(prefix: String): CodexUsageWindow? {
        val used = headers["x-codex-$prefix-used-percent"]?.toDoubleOrNull() ?: return null
        val windowMinutes = headers["x-codex-$prefix-window-minutes"]?.toLongOrNull()
        if (windowMinutes != null && windowMinutes <= 0) return null
        val resetsAt = headers["x-codex-$prefix-reset-at"]?.toLongOrNull()
            ?: headers["x-codex-$prefix-reset-after-seconds"]?.toLongOrNull()
                ?.let { System.currentTimeMillis() / 1000 + it }
        return CodexUsageWindow(
            usedPercent = used,
            windowMinutes = windowMinutes,
            resetsAt = resetsAt,
        )
    }
    val primary = window("primary")
    val secondary = window("secondary")
    if (primary == null && secondary == null) return null
    return CodexUsageSnapshot(primary = primary, secondary = secondary)
}

private fun JsonObject.toUsageWindow(): CodexUsageWindow? {
    val used = this["used_percent"]?.jsonPrimitive?.doubleOrNull ?: return null
    val windowSeconds = this["limit_window_seconds"]?.jsonPrimitive?.longOrNull
    if (windowSeconds != null && windowSeconds <= 0) return null
    return CodexUsageWindow(
        usedPercent = used,
        windowMinutes = windowSeconds?.div(60),
        resetsAt = this["reset_at"]?.jsonPrimitive?.longOrNull,
    )
}
