package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import io.ratex.RaTeXFontLoader

/**
 * 注册打包在 APK 中的 Noto Serif CJK SC Light 作为 RaTeX CJK 回退字体。
 */
fun registerSerifCjkFallback(context: Context) {
    val bytes = context.assets.open("fonts/NotoSerifCJKsc-Light.otf").readBytes()
    if (bytes.isNotEmpty()) {
        RaTeXFontLoader.registerCjkFallbackFont(bytes)
    }
}
