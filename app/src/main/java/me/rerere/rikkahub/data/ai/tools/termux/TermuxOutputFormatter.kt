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
}
