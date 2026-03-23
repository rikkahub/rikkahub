package me.rerere.sandbox

import java.io.File

data class SandboxBind(
    val hostPath: String,
    val guestPath: String? = null,
    val dereferenceGuestPath: Boolean = true,
) {
    constructor(
        host: File,
        guestPath: String? = null,
        dereferenceGuestPath: Boolean = true,
    ) : this(
        hostPath = host.absolutePath,
        guestPath = guestPath,
        dereferenceGuestPath = dereferenceGuestPath,
    )

    fun toProotArgument(): String {
        val guest = guestPath?.takeIf { it.isNotBlank() } ?: return hostPath
        return buildString {
            append(hostPath)
            append(':')
            append(guest)
            if (!dereferenceGuestPath) {
                append('!')
            }
        }
    }
}
