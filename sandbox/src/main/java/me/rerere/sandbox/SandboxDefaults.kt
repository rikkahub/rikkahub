package me.rerere.sandbox

internal object SandboxDefaults {
    val defaultHostBindings = listOf(
        SandboxBind("/dev"),
        SandboxBind("/proc"),
        SandboxBind("/sys"),
    )

    fun guestEnvironment(config: SandboxConfig): LinkedHashMap<String, String> {
        return linkedMapOf(
            "HOME" to config.homeDirectory,
            "USER" to if (config.fakeRoot) "root" else "sandbox",
            "LOGNAME" to if (config.fakeRoot) "root" else "sandbox",
            "PATH" to config.defaultPath,
            "TERM" to config.terminal,
            "TMPDIR" to "/tmp",
        ).apply {
            putAll(config.guestEnvironment)
        }
    }
}
