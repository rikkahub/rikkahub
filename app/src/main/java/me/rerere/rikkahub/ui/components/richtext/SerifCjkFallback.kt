package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Typeface
import android.os.Build
import io.ratex.RaTeXFontLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册系统默认衬线体（[Typeface.SERIF]）作为 RaTeX 的 CJK 回退字体。
 *
 * 国产 ROM 上 [Typeface.SERIF] 的系统 fallback 链包含带 CJK 字形的衬线字体，
 * 中文字符可正确渲染为衬线体。全球版 ROM 上回退到 Roboto（无衬线），功能正常。
 *
 * 步骤：
 * 1. [RaTeXFontLoader.ensureLoaded] 确保 KaTeX 字体已加载
 * 2. 把 [Typeface.SERIF] 注册进 FontCache 作为 CJK-Regular / CJK-Fallback
 * 3. 反射遍历 FontCache，用 Typeface.Builder(Typeface).setFallback(Typeface).build()
 *    把各 KaTeX 字体包装为"优先系统衬线体，缺字回退 KaTeX"的复合字体
 */
suspend fun registerSerifCjkFallback() {
    RaTeXFontLoader.ensureLoaded()
    registerSystemSerifAsCjkFallback()

    if (Build.VERSION.SDK_INT >= 29) {
        reflectWrapKaTeXFonts()
    }
}

/**
 * KaTeX 字体 ID 常量（与 RaTeXFontLoader.kt 对齐）
 */
private const val FONT_ID_CJK_REGULAR = "CJK-Regular"
private const val FONT_ID_CJK_FALLBACK = "CJK-Fallback"
private const val FONT_ID_EMOJI_FALLBACK = "Emoji-Fallback"
private val FALLBACK_FONT_IDS = setOf(
    FONT_ID_CJK_REGULAR, FONT_ID_CJK_FALLBACK, FONT_ID_EMOJI_FALLBACK,
)

/**
 * 把系统衬线体放入 FontCache，使 [RaTeXFontLoader.getPlatformTypeFace] 查找
 * CJK-Regular / CJK-Fallback 时能拿到。
 */
private fun registerSystemSerifAsCjkFallback() {
    try {
        val cache = getFontCache()
        val serif = Typeface.SERIF
        cache[FONT_ID_CJK_REGULAR] = serif
        cache[FONT_ID_CJK_FALLBACK] = serif
    } catch (_: Exception) {
    }
}

/**
 * 反射：给每个 KaTeX 字体附加 CJK fallback。
 * 使用 [Typeface.Builder]([Typeface]) + setFallback(Typeface) + build()，
 * 均为 framework hidden API，R8 永不混淆。
 *
 * 构造语义：基底 = Typeface.SERIF（优先显示系统衬线体），
 * fallback = 原始 KaTeX 字体（衬线体缺字时回退到 KaTeX 数学符号）。
 */
private fun reflectWrapKaTeXFonts() {
    try {
        val cache = getFontCache()

        val builderClass = Class.forName("android.graphics.Typeface\$Builder")
        val constructor = builderClass.getConstructor(Typeface::class.java)
        val setFallbackMethod = builderClass.getMethod("setFallback", Typeface::class.java)
        val buildMethod = builderClass.getMethod("build")

        for ((fontId, originalTypeface) in cache) {
            if (fontId in FALLBACK_FONT_IDS) continue

            val builder = constructor.newInstance(Typeface.SERIF)
            setFallbackMethod.invoke(builder, originalTypeface)
            cache[fontId] = buildMethod.invoke(builder) as Typeface
        }
    } catch (_: Exception) {
    }
}

@Suppress("UNCHECKED_CAST")
private fun getFontCache(): ConcurrentHashMap<String, Typeface> {
    val fontCacheClass = Class.forName("io.ratex.FontCache")
    val instanceField = fontCacheClass.getDeclaredField("INSTANCE")
    instanceField.isAccessible = true
    val fontCache = instanceField.get(null)

    val cacheField = fontCacheClass.getDeclaredField("cache")
    cacheField.isAccessible = true
    return cacheField.get(fontCache) as ConcurrentHashMap<String, Typeface>
}
