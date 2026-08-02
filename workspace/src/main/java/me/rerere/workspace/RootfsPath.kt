package me.rerere.workspace

/** Canonical POSIX path used for every Rootfs guest-path policy and resolution decision. */
object RootfsPath {
    fun normalize(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        require(normalized.isNotBlank()) { "Rootfs path is required" }
        require(!normalized.contains('\u0000')) { "Rootfs path contains invalid character" }
        require(normalized.startsWith('/')) { "Rootfs path must be absolute: $path" }

        val segments = ArrayDeque<String>()
        normalized.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
    }

    fun isWithin(path: String, root: String): Boolean {
        val normalizedPath = normalize(path)
        val normalizedRoot = normalize(root)
        return normalizedRoot == "/" ||
            normalizedPath == normalizedRoot ||
            normalizedPath.startsWith("$normalizedRoot/")
    }
}
