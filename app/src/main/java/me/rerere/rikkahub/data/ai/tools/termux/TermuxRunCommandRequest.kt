package me.rerere.rikkahub.data.ai.tools.termux

data class TermuxRunCommandRequest(
    val commandPath: String,
    val arguments: List<String> = emptyList(),
    val workdir: String,
    val stdin: String? = null,
    val background: Boolean = true,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val label: String? = null,
    val description: String? = null,
    val trackLifecycle: Boolean = true,
)

const val DEFAULT_TIMEOUT_MS = 120_000L
