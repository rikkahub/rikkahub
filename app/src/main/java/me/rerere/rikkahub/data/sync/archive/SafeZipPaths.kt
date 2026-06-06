package me.rerere.rikkahub.data.sync.archive

import java.io.File
import java.io.IOException

/**
 * Centralized boundary check for ZIP entry names during backup restore.
 *
 * The historic upload restore path resolved targets with `File(uploadFolder,
 * fileName)` and no canonical containment check, so a crafted entry such as
 * `upload/../../evil` could escape the upload root. This object is the single
 * place that enforces the containment invariant for every restore root.
 */
object SafeZipPaths {
    /**
     * A ZIP entry's relative path is safe when it is non-blank, contains no
     * control character (NUL et al. — rejected so a crafted `upload/evil\u0000.txt`
     * cannot reach canonicalization, where the JVM throws `IOException` and would
     * abort the whole restore), not absolute, contains no backslash (rejected so
     * that `upload/..\evil` cannot smuggle a traversal on platforms where `\` is
     * not a separator), and every `/`-separated segment is a normal name (not
     * empty, `.`, or `..`).
     */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.any { it.isISOControl() }) return false
        if (name.startsWith('/') || File(name).isAbsolute) return false
        if (name.contains('\\')) return false

        val segments = name.split('/')
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /**
     * Resolves [relativePath] under [root], returning the target file only when
     * it is [root] itself or strictly inside it. Returns null for any unsafe or
     * escaping path. Mirrors the containment helper in `SkillPaths`.
     */
    fun resolveChild(root: File, relativePath: String): File? {
        if (!isSafeEntryName(relativePath)) return null

        val canonicalRoot: File
        val target: File
        try {
            canonicalRoot = root.canonicalFile
            target = canonicalRoot.resolve(relativePath).canonicalFile
        } catch (_: IOException) {
            // Fail closed: any name the OS refuses to canonicalize is treated as
            // unsafe so the caller skips it instead of letting the IOException
            // propagate and abort the whole restore.
            return null
        }

        return target.takeIf {
            it.path == canonicalRoot.path ||
                it.path.startsWith(canonicalRoot.path + File.separator)
        }
    }
}
