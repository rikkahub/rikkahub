package me.rerere.rikkahub.voiceagent.telemetry

import java.security.MessageDigest

internal data class HermesToolResponseHashResult(
    val sha256Hex: String,
    val normalizedChars: Int,
    val expectedHashMatch: Boolean?,
)

internal object HermesToolResponseHash {
    private val whitespace = Regex("\\s+")

    fun normalize(answer: String): String =
        answer.trim().replace(whitespace, " ")

    fun sha256HexNormalized(answer: String): String =
        sha256Hex(normalize(answer))

    fun calculate(answer: String, expectedSha256: String?): HermesToolResponseHashResult {
        val normalized = normalize(answer)
        val hash = sha256Hex(normalized)
        val expected = expectedSha256
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        return HermesToolResponseHashResult(
            sha256Hex = hash,
            normalizedChars = normalized.length,
            expectedHashMatch = expected?.let(hash::equals),
        )
    }

    fun diagnosticDetail(
        callId: String,
        answer: String,
        expectedSha256: String?,
        elapsedMs: Long,
        serverElapsedMs: Long?,
    ): String {
        val result = calculate(answer = answer, expectedSha256 = expectedSha256)
        val expectedMatch = result.expectedHashMatch?.let { ", expectedHashMatch=$it" }.orEmpty()
        val serverElapsed = serverElapsedMs?.let { ", serverElapsedMs=$it" }.orEmpty()
        return "callId=$callId, responseChars=${answer.length}, " +
            "normalizedChars=${result.normalizedChars}, actualHash=${result.sha256Hex}" +
            "$expectedMatch, elapsedMs=$elapsedMs$serverElapsed"
    }

    fun requestDiagnosticDetail(callId: String, prompt: String): String {
        val normalized = normalize(prompt)
        return "callId=$callId, promptChars=${prompt.length}, " +
            "normalizedChars=${normalized.length}, promptHash=${sha256Hex(normalized)}"
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
