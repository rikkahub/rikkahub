package me.rerere.rikkahub.web.routes

import kotlin.uuid.Uuid
import me.rerere.rikkahub.web.BadRequestException
import java.io.File

internal fun String?.toUuid(name: String = "id"): Uuid {
    if (this == null) throw BadRequestException("Missing $name")
    return runCatching { Uuid.parse(this) }.getOrNull()
        ?: throw BadRequestException("Invalid $name")
}

/**
 * Returns true iff [target] is the root directory itself or lives canonically inside [root].
 *
 * Canonical paths are compared so symlinks and `.`/`..` segments are resolved before the boundary
 * test. The separator-aware comparison is the security-relevant part: a raw `startsWith` accepts a
 * SIBLING whose name shares a prefix with the root (e.g. `/data/files_evil` vs root `/data/files`),
 * which is the directory-escape this predicate exists to reject.
 *
 * [File.getCanonicalPath] can throw IOException; callers run inside the route handler whose
 * framework-level exception handling owns that failure path — it must not be swallowed here.
 */
internal fun isPathWithin(root: File, target: File): Boolean {
    val rootPath = root.canonicalPath
    val targetPath = target.canonicalPath
    return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
}
