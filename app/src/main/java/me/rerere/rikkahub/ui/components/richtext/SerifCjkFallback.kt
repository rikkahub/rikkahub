package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import io.ratex.RaTeXFontLoader
import java.io.File

/**
 * 从系统字体目录加载衬线体（Serif）作为 RaTeX CJK 回退字体。
 *
 * RaTeX 渲染公式中的中文字符时，默认使用系统无衬线体（[Typeface.DEFAULT]）。
 * 此函数尝试找到系统衬线体文件并注册到 RaTeX 的 CJK 回退字体槽位，
 * 使公式中的中文也显示为衬线体，与聊天设置的"衬线体"选项一致。
 *
 * 应在后台线程调用（内部会读文件）。
 */
fun registerSerifCjkFallback(context: Context) {
    val serifFile = findSerifCjkFontFile() ?: return
    val bytes = serifFile.readBytes()
    if (bytes.isNotEmpty()) {
        RaTeXFontLoader.registerCjkFallbackFont(bytes)
    }
}

/**
 * 尝试常见的 Android 系统衬线字体路径。
 * 按优先级：专用 CJK 衬线体 > 通用衬线体
 */
private fun findSerifCjkFontFile(): File? {
    val candidates = listOf(
        // Android 10+ 专用 CJK 衬线体 (TTC 合集, index 0 = Regular)
        File("/system/fonts/NotoSerifCJK-Regular.ttc"),
        // 旧版 Android / 部分 OEM
        File("/system/fonts/DroidSerif-Regular.ttf"),
        // 通用衬线体（含 CJK 的子集）
        File("/system/fonts/NotoSerif-Regular.ttf"),
        File("/system/fonts/NotoSerif-Regular.otf"),
    )
    return candidates.firstOrNull { it.exists() && it.length() > 0 }
}
