package me.rerere.rikkahub.data.codex

import kotlinx.serialization.Serializable

@Serializable
data class CodexAccount(
    val id: String,
    val userId: String = "",
    val name: String,
    val email: String = "",
    val chatgptAccountId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: CodexTokenStatus = CodexTokenStatus.UNKNOWN,
    val usage: CodexUsageSnapshot? = null,
)

@Serializable
enum class CodexTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

@Serializable
data class CodexUsageSnapshot(
    val primary: CodexUsageWindow? = null,
    val secondary: CodexUsageWindow? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class CodexUsageWindow(
    val usedPercent: Double,
    val windowMinutes: Long? = null,
    val resetsAt: Long? = null,
)

internal fun CodexAccount.isAvailable(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!enabled || tokenStatus == CodexTokenStatus.INVALID) return false
    val exhausted = listOfNotNull(usage?.primary, usage?.secondary)
        .any { it.usedPercent >= 100.0 && (it.resetsAt == null || it.resetsAt * 1000 > nowMillis) }
    return !exhausted
}
