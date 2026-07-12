package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Paint
import android.graphics.Typeface
import io.ratex.RaTeXFontLoader
import java.io.File

/**
 * 注册系统衬线体作为 RaTeX CJK 回退字体。
 *
 * 与 `ChatFontFamily.SERIF → FontFamily.Serif` 使用同一字体，
 * 使公式中的中文与聊天设置的"衬线体"选项一致。
 */
fun registerSerifCjkFallback() {
    val fontFile = findSystemSerifCjkFile() ?: return
    val bytes = fontFile.readBytes()
    if (bytes.isNotEmpty()) {
        RaTeXFontLoader.registerCjkFallbackFont(bytes)
    }
}

/**
 * 在 /system/fonts/ 中查找支持 CJK 的系统衬线字体文件。
 *
 * 搜索策略：
 * 1. 优先匹配文件名含 "SerifCJK" 的字体（明确支持中日韩的衬线体）
 * 2. 其次匹配文件名含 "Serif" 的字体（通用衬线体）
 * 3. 用 Paint.hasGlyph() 验证是否确实包含 CJK 字形
 */
private fun findSystemSerifCjkFile(): File? {
    val fontsDir = File("/system/fonts/")
    if (!fontsDir.isDirectory) return null

    val cjkTestChar = 0x4E2D // 中

    // 按优先级分组扫描
    val serifFiles = fontsDir.listFiles()?.filter { file ->
        file.isFile && file.length() > 0 && file.name.contains("Serif", ignoreCase = true)
    }?.sortedByDescending { file ->
        // 含 CJK 的优先
        if (file.name.contains("CJK", ignoreCase = true)) 1 else 0
    } ?: return null

    // 用 hasGlyph 验证第一个可用的衬线 CJK 字体
    val paint = Paint()
    for (file in serifFiles) {
        try {
            val typeface = Typeface.createFromFile(file)
            paint.typeface = typeface
            if (paint.hasGlyph(cjkTestChar.toString())) {
                return file
            }
        } catch (_: Exception) {
            // 某些 .ttc 文件可能无法直接用 createFromFile 打开
            // 尝试作为 fallback 仍可返回
            if (serifFiles.size == 1) return file
        }
    }

    // 没有找到确认支持 CJK 的衬线字体，返回第一个衬线文件碰碰运气
    return serifFiles.firstOrNull()
}
