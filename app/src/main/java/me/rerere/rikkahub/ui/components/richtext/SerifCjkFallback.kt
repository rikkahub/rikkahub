package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import io.ratex.RaTeXFontLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册 Noto Serif CJK SC Light 作为 RaTeX 所有 KaTeX 字体的 CJK 回退。
 *
 * 两步：
 * 1. 调用 [RaTeXFontLoader.ensureLoaded] 让 RaTeX 加载 KaTeX 字体到 FontCache
 * 2. 通过反射将 FontCache 中所有字体包裹 [Typeface.Builder.setFallback]，使公式中的
 *    中文显示为衬线体而非系统默认的无衬线体
 */
suspend fun registerSerifCjkFallback(context: Context) {
    // 1. 确保 RaTeX KaTeX 字体已加载
    RaTeXFontLoader.ensureLoaded()

    // 2. 读我们的 CJK 字体
    val cjkBytes = context.assets.open("fonts/NotoSerifCJKsc-Light.otf").readBytes()
    if (cjkBytes.isEmpty()) return

    // 3. 标准注册（覆盖 CJK-Fallback / CJK-Regular font ID）
    RaTeXFontLoader.registerCjkFallbackFont(cjkBytes)

    // 4. 通过反射给所有 KaTeX 字体附加 CJK 回退（API 29+）
    if (Build.VERSION.SDK_INT >= 29) {
        val tempFile = File(context.cacheDir, "ratex-cjk.otf").apply {
            parentFile?.mkdirs()
            writeBytes(cjkBytes)
        }
        val cjkTypeface = Typeface.createFromFile(tempFile)

        try {
            val fontCacheClass = Class.forName("io.ratex.FontCache")
            val instanceField = fontCacheClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val fontCache = instanceField.get(null)

            val cacheField = fontCacheClass.getDeclaredField("cache")
            cacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = cacheField.get(fontCache) as ConcurrentHashMap<String, Typeface>

            for ((fontId, originalTypeface) in cache) {
                cache[fontId] = Typeface.Builder(originalTypeface, 0)
                    .setFallback(cjkTypeface)
                    .build()
            }
        } catch (_: Exception) {
            // 反射失败：registerCjkFallbackFont 已执行，保持兜底
        }
    }
}
