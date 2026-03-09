package me.rerere.rikkahub.data.ai.tools.termux

object TermuxOutputFormatter {
    fun merge(
        stdout: String,
        stderr: String,
        errMsg: String? = null,
    ): String {
        return buildList {
            stdout.trimEnd().takeIf { it.isNotBlank() }?.let(::add)
            stderr.trimEnd().takeIf { it.isNotBlank() }?.let(::add)
            errMsg?.trimEnd()?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(separator = "\n")
    }

    fun statusSummary(result: TermuxResult): String {
        return buildList {
            if (result.timedOut) add("Timed out")
            result.exitCode?.takeIf { it != 0 }?.let { add("Exit code: $it") }
            result.errCode?.takeIf { result.hasInternalError() }?.let { add("Err code: $it") }
            result.errMsg?.trimEnd()?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(separator = "\n")
    }
}
