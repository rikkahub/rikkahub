package me.rerere.sandbox

sealed class SandboxOutput {
    data class Stdout(val text: String) : SandboxOutput()
    data class Stderr(val text: String) : SandboxOutput()
    data class Exit(val code: Int) : SandboxOutput()
}
