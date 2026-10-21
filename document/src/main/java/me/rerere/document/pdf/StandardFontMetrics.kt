package me.rerere.document.pdf

/**
 * 标准 14 字体（及 Arial、Times New Roman 等常见替代名）的字宽，
 * 用于没有 Widths 的非嵌入字体，避免估算误差让字形位置漂移。
 */
internal object StandardFontMetrics {
    private val fonts: Map<String, Map<Int, Float>> by lazy { load() }

    /** 返回字体的 Unicode → 字宽（已除以 1000）表；非标准字体返回 null */
    fun widths(baseFont: String): Map<Int, Float>? {
        val name = baseFont.substringAfter('+').replace(" ", "")
        val lower = name.lowercase()
        val bold = "bold" in lower || "black" in lower || "heavy" in lower
        val italic = "italic" in lower || "oblique" in lower
        val family = when {
            lower.startsWith("courier") -> return COURIER
            lower.startsWith("symbol") -> "Symbol"
            lower.startsWith("zapfdingbats") || lower.startsWith("dingbats") -> "ZapfDingbats"
            lower.startsWith("times") -> "Times"
            lower.startsWith("helvetica") || lower.startsWith("arial") -> "Helvetica"
            else -> return null
        }
        val key = when (family) {
            "Helvetica" -> if (bold) "Helvetica-Bold" else "Helvetica"
            "Times" -> when {
                bold && italic -> "Times-BoldItalic"
                bold -> "Times-Bold"
                italic -> "Times-Italic"
                else -> "Times-Roman"
            }

            else -> family
        }
        return fonts[key]
    }

    // Courier 全部等宽 600
    private val COURIER = object : AbstractMap<Int, Float>() {
        override val entries: Set<Map.Entry<Int, Float>> get() = emptySet()
        override fun get(key: Int): Float = 0.6f
        override fun containsKey(key: Int): Boolean = true
    }

    private fun load(): Map<String, Map<Int, Float>> {
        val result = HashMap<String, Map<Int, Float>>()
        val stream =
            StandardFontMetrics::class.java.getResourceAsStream("$RESOURCE_DIR/core14-widths.txt") ?: return result
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val map = HashMap<Int, Float>()
                for (pair in line.substring(tab + 1).split(',')) {
                    val colon = pair.indexOf(':')
                    if (colon <= 0) continue
                    map[pair.substring(0, colon).toInt(16)] = pair.substring(colon + 1).toInt() / 1000f
                }
                result[line.substring(0, tab)] = map
            }
        }
        return result
    }
}
