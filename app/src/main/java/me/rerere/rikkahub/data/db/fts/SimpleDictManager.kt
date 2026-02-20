package me.rerere.rikkahub.data.db.fts

import android.content.Context
import java.io.File

object SimpleDictManager {

    private const val DICT_ASSET_DIR = "simple_dict"
    private const val VERSION_FILE = "version.txt"

    // 与 assets 中的词典版本对齐，更新词典时递增此值
    private const val CURRENT_VERSION = 1

    /**
     * 将 assets/simple_dict 解压到 files/simple_dict，返回词典目录。
     * 已是最新版本时直接返回，无需重复拷贝。
     * 可在任意线程调用。
     */
    fun extractDict(context: Context): File {
        val destDir = File(context.filesDir, DICT_ASSET_DIR)
        val versionFile = File(destDir, VERSION_FILE)

        if (versionFile.exists() && versionFile.readText().trim().toIntOrNull() == CURRENT_VERSION) {
            return destDir
        }

        destDir.deleteRecursively()
        destDir.mkdirs()
        copyAssetDir(context, DICT_ASSET_DIR, destDir)
        versionFile.writeText(CURRENT_VERSION.toString())
        return destDir
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        for (name in assets) {
            val childAsset = "$assetPath/$name"
            val destFile = File(destDir, name)
            val children = context.assets.list(childAsset)
            if (!children.isNullOrEmpty()) {
                destFile.mkdirs()
                copyAssetDir(context, childAsset, destFile)
            } else {
                context.assets.open(childAsset).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
