package me.rerere.sandbox

import java.io.File

data class SandboxBinaries(
    val proot: File,
    val prootUserland: File,
    val loader: File,
    val loader32: File?,
) {
    fun executable(useUserlandBinary: Boolean): File {
        return if (useUserlandBinary) prootUserland else proot
    }
}
