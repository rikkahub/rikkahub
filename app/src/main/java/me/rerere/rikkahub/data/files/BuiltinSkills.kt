package me.rerere.rikkahub.data.files

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException

/**
 * 内置技能：以 `assets/builtin_skills/<name>/` 随 APK 发布，目录结构与用户技能相同（SKILL.md + 附属文件）。
 *
 * App 安装/更新后解压到 `filesDir/builtin_skills/`，并挂载到 workspace 的 `/builtin_skills`，
 * 这样附属脚本可以在 workspace 中执行。内置技能只读，与用户技能同名时以用户技能为准。
 *
 * 注意：aapt 默认会忽略以 `.` 开头的文件和以 `_` 开头的目录，不要用这类名称。
 */
internal object BuiltinSkills {
    private const val ASSETS_ROOT = "builtin_skills"
    private const val VERSION_FILE = ".version"

    /**
     * 当 App 安装/更新时间变化时，将 assets 中的内置技能完整解压到 [targetDir]，替换旧内容。
     *
     * 用 lastUpdateTime 而非 versionCode 判断，保证开发时每次重新安装都会刷新。
     */
    fun extractIfNeeded(context: Context, targetDir: File) {
        val stamp = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime
            .toString()
        val versionFile = targetDir.resolve(VERSION_FILE)
        if (versionFile.exists() && versionFile.readText() == stamp) return

        // 先完整解压到临时目录再替换，避免中途失败留下半套文件
        val staging = targetDir.resolveSibling(".${targetDir.name}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val assets = context.assets
        assets.list(ASSETS_ROOT).orEmpty().forEach { name ->
            assets.copyTree("$ASSETS_ROOT/$name", staging.resolve(name))
        }
        staging.resolve(VERSION_FILE).writeText(stamp)

        targetDir.deleteRecursively()
        if (!staging.renameTo(targetDir)) {
            staging.deleteRecursively()
            error("Failed to move builtin skills into ${targetDir.absolutePath}")
        }
    }

    private fun AssetManager.copyTree(assetPath: String, target: File) {
        val children = list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            target.mkdirs()
            children.forEach { copyTree("$assetPath/$it", target.resolve(it)) }
            return
        }
        // AssetManager 无法区分文件和空目录：list 为空时先按文件打开，打不开则视为空目录
        try {
            open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (_: FileNotFoundException) {
            target.mkdirs()
            return
        }
        // assets 不保留可执行位，带 shebang 的脚本需要手动恢复，才能在 workspace 中直接 ./script 执行
        if (target.startsWithShebang()) {
            target.setExecutable(true, false)
        }
    }

    private fun File.startsWithShebang(): Boolean = inputStream().use { input ->
        input.read() == '#'.code && input.read() == '!'.code
    }
}

/**
 * 合并用户技能与内置技能，用户技能在前；与用户技能同名的内置技能会被隐藏。
 */
internal fun mergeWithBuiltinSkills(
    local: List<SkillMetadata>,
    builtin: List<SkillMetadata>,
): List<SkillMetadata> {
    val localNames = local.mapTo(HashSet()) { it.name }
    return local + builtin.filter { it.name !in localNames }
}
