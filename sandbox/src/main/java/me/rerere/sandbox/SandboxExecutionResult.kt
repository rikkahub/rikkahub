package me.rerere.sandbox

data class SandboxExecutionResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val succeeded: Boolean
        get() = !timedOut && exitCode == 0
}
