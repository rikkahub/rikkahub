package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import io.ratex.RaTeXFontLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册 Noto Serif CJK SC Light 作为 RaTeX 的 CJK 回退字体。
 *
 * 顺序：ensureLoaded → registerCjkFallbackFont → 反射包装现有字体。
 * [ensureLoaded] 确保 FontCache 中已有 KaTeX 字体，否则反射遍历空 map 无效。
 */
suspend fun registerSerifCjkFallback(context: Context) {
    // 1. 先确保 KaTeX 字体已加载
    RaTeXFontLoader.ensureLoaded()

    val cjkBytes = context.assets.open("fonts/NotoSerifCJKsc-Regular.otf").readBytes()
    if (cjkBytes.isEmpty()) return

    // 2. 标准注册（兜底 CJK-Fallback font ID）
    RaTeXFontLoader.registerCjkFallbackFont(cjkBytes)

    // 3. 反射：给每个 KaTeX 字体附加 CJK fallback
    if (Build.VERSION.SDK_INT >= 29) {
        val tempFile = File(context.cacheDir, "ratex-cjk.otf").apply {
            parentFile?.mkdirs()
            writeBytes(cjkBytes)
        }

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
                val builder = Typeface.Builder(tempFile.absolutePath)
                builder::class.java
                    .getMethod("setFallback", Typeface::class.java)
                    .invoke(builder, originalTypeface)
                cache[fontId] = builder::class.java
                    .getMethod("build")
                    .invoke(builder) as Typeface
            }
        } catch (_: Exception) {
            // 反射失败不影响 registerCjkFallbackFont
        }
    }
}
