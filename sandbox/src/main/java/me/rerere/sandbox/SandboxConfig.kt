package me.rerere.sandbox

import java.io.File

data class SandboxConfig(
    val rootfsDir: File,
    val workingDirectory: String = "/",
    val bindMounts: List<SandboxBind> = emptyList(),
    val guestEnvironment: Map<String, String> = emptyMap(),
    val hostEnvironment: Map<String, String> = emptyMap(),
    val useUserlandBinary: Boolean = true,
    val fakeRoot: Boolean = true,
    val killOnExit: Boolean = true,
    val clearGuestEnvironment: Boolean = true,
    val useDefaultBindings: Boolean = true,
    val homeDirectory: String = "/root",
    val defaultPath: String = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    val terminal: String = "xterm-256color",
    val verboseLevel: Int? = null,
    val launchDirectory: File? = null,
    val prootTmpDir: File? = null,
) {
    init {
        require(workingDirectory.isNotBlank()) { "workingDirectory must not be blank" }
        require(homeDirectory.isNotBlank()) { "homeDirectory must not be blank" }
        require(defaultPath.isNotBlank()) { "defaultPath must not be blank" }
        verboseLevel?.let { require(it >= -1) { "verboseLevel must be >= -1" } }
    }
}
