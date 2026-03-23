package me.rerere.sandbox

import java.io.File

object SandboxCommandBuilder {
    fun build(
        binaries: SandboxBinaries,
        config: SandboxConfig,
        guestCommand: List<String>,
    ): List<String> {
        require(config.rootfsDir.isDirectory) {
            "rootfs directory does not exist: ${config.rootfsDir.absolutePath}"
        }
        require(guestCommand.isNotEmpty()) { "guestCommand must not be empty" }

        val args = mutableListOf<String>()
        args += binaries.executable(config.useUserlandBinary).absolutePath

        if (config.killOnExit) {
            args += "--kill-on-exit"
        }
        config.verboseLevel?.let {
            args += listOf("-v", it.toString())
        }
        if (config.fakeRoot) {
            args += "-0"
        }
        args += "-l" // --link2symlink: convert hardlinks to symlinks (needed on Android)

        args += listOf("-r", config.rootfsDir.absolutePath)
        args += listOf("-w", config.workingDirectory)

        resolvedBindings(config).forEach { bind ->
            args += listOf("-b", bind.toProotArgument())
        }

        args += "/usr/bin/env"
        if (config.clearGuestEnvironment) {
            args += "-i"
        }
        SandboxDefaults.guestEnvironment(config).forEach { (key, value) ->
            args += "$key=$value"
        }
        args += guestCommand

        return args
    }

    internal fun resolvedBindings(config: SandboxConfig): List<SandboxBind> {
        return buildList {
            if (config.useDefaultBindings) {
                addAll(SandboxDefaults.defaultHostBindings)
            }
            addAll(config.bindMounts)
        }.filter { File(it.hostPath).exists() }
            .distinctBy { Triple(it.hostPath, it.guestPath, it.dereferenceGuestPath) }
    }
}
