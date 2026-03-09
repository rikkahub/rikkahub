package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.Serializable

const val TERMUX_RESULT_OK = -1

@Serializable
data class TermuxResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val errCode: Int? = null,
    val errMsg: String? = null,
    val stdoutOriginalLength: Int? = null,
    val stderrOriginalLength: Int? = null,
    val timedOut: Boolean = false,
)

fun TermuxResult.hasInternalError(): Boolean {
    return errCode != null && errCode != TERMUX_RESULT_OK
}

fun TermuxResult.isSuccessful(): Boolean {
    if (timedOut) return false
    if (hasInternalError()) return false
    if (!errMsg.isNullOrBlank()) return false
    return exitCode == null || exitCode == 0
}
