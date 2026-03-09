package me.rerere.rikkahub.data.ai.tools.termux

const val TERMUX_RESULT_OK = -1

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
