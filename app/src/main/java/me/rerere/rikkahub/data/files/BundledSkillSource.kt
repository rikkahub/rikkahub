package me.rerere.rikkahub.data.files

import android.content.Context
import java.io.File

/**
 * Extracts the app-bundled (builtin) skills shipped under `assets/builtin-skills/` into a read-only root
 * under `filesDir`, so they read through the SAME File-based machinery as user skills ([SkillPaths],
 * [SkillManager.readSkillBody], [SkillManager.resolveSkillFile]). It lives in a SEPARATE root from the
 * user skills dir ([SkillManager.getSkillsDir]) so a mutation can NEVER write into a builtin — the
 * read-only guarantee is structural, not a runtime check.
 *
 * Version-gated like [me.rerere.rikkahub.data.db.fts.SimpleDictManager]: it re-extracts only when
 * [BUNDLED_SKILLS_VERSION] changes (wiping ONLY this builtin root, never the user skills dir), so a new
 * app build ships updated bundled content while leaving user skills untouched. Idempotent and cheap
 * after the first call (an in-process flag plus the on-disk version marker); safe on any thread.
 */
internal class BundledSkillSource(private val context: Context) {
    @Volatile
    private var ensured = false

    fun builtinRoot(): File = File(context.filesDir, BUILTIN_DIR)

    fun ensureExtracted(): File {
        val root = builtinRoot()
        if (ensured) return root
        synchronized(this) {
            if (ensured) return root
            val versionFile = File(root, VERSION_FILE)
            val upToDate = versionFile.exists() &&
                versionFile.readText().trim().toIntOrNull() == BUNDLED_SKILLS_VERSION
            if (!upToDate) {
                root.deleteRecursively()
                root.mkdirs()
                copyAssetDir(ASSET_DIR, root)
                versionFile.writeText(BUNDLED_SKILLS_VERSION.toString())
            }
            ensured = true
        }
        return root
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val destFile = File(destDir, name)
            val children = context.assets.list(childAsset)
            if (!children.isNullOrEmpty()) {
                destFile.mkdirs()
                copyAssetDir(childAsset, destFile)
            } else {
                context.assets.open(childAsset).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    companion object {
        private const val ASSET_DIR = "builtin-skills"
        private const val BUILTIN_DIR = "skills_builtin"
        private const val VERSION_FILE = ".version"

        // Bump when the bundled skill content changes so installed apps re-extract the new copy.
        private const val BUNDLED_SKILLS_VERSION = 1
    }
}
