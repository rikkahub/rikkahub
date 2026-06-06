package me.rerere.rikkahub.data.files

import java.io.File
import java.io.IOException

internal object SkillPaths {
    fun resolveSkillDir(skillsRoot: File, skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName.any { it.isISOControl() }) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null

        return try {
            val canonicalRoot = skillsRoot.canonicalFile
            val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
            val parent = canonicalDir.parentFile ?: return null

            if (parent != canonicalRoot) return null
            if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

            canonicalDir
        } catch (_: IOException) {
            // Fail closed: a name the OS refuses to canonicalize (e.g. a NUL
            // byte) is unsafe, so the caller skips it instead of letting the
            // IOException propagate and abort the whole restore.
            null
        }
    }

    fun resolveSkillFile(skillDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        if (relativePath.any { it.isISOControl() }) return null

        return try {
            val canonicalSkillDir = skillDir.canonicalFile
            val canonicalTarget = canonicalSkillDir.resolve(relativePath).canonicalFile

            canonicalTarget.takeIf { it.isSameOrInside(canonicalSkillDir) }
        } catch (_: IOException) {
            // Fail closed: see resolveSkillDir.
            null
        }
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}
