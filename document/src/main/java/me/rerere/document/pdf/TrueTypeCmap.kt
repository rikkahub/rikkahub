package me.rerere.document.pdf

/**
 * 解析嵌入 TrueType 字体的 cmap 表，用于没有 ToUnicode 时把字形反查回 Unicode。
 */
internal class TrueTypeCmap private constructor(
    // (3,1)/(3,10)/(0,*) 子表：Unicode → glyph
    val unicode: Map<Int, Int>,
    // (3,0) 子表：符号字体编码 → glyph
    val symbol: Map<Int, Int>,
    // (1,0) 子表：Mac Roman 编码 → glyph
    val mac: Map<Int, Int>,
) {
    private val glyphToUnicode: Map<Int, Int> by lazy {
        val reverse = HashMap<Int, Int>(unicode.size * 2)
        for ((cp, gid) in unicode) {
            if (gid == 0) continue
            val existing = reverse[gid]
            // 同一字形对应多个码位时优先非私用区、较小的码位
            if (existing == null || (isPrivateUse(existing) && !isPrivateUse(cp)) ||
                (isPrivateUse(existing) == isPrivateUse(cp) && cp < existing)) {
                reverse[gid] = cp
            }
        }
        reverse
    }

    fun unicodeOfGlyph(gid: Int): String? = glyphToUnicode[gid]?.let { String(Character.toChars(it)) }

    private fun isPrivateUse(cp: Int) = cp in 0xE000..0xF8FF

    companion object {
        fun parse(font: ByteArray): TrueTypeCmap? = try {
            parseInternal(font)
        } catch (_: Exception) {
            null
        }

        private fun parseInternal(font: ByteArray): TrueTypeCmap? {
            if (font.size < 12) return null
            val numTables = u16(font, 4)
            var cmapOffset = -1
            for (i in 0 until numTables) {
                val rec = 12 + i * 16
                if (rec + 16 > font.size) break
                if (font[rec] == 'c'.code.toByte() && font[rec + 1] == 'm'.code.toByte() &&
                    font[rec + 2] == 'a'.code.toByte() && font[rec + 3] == 'p'.code.toByte()
                ) {
                    cmapOffset = u32(font, rec + 8).toInt()
                }
            }
            if (cmapOffset < 0 || cmapOffset + 4 > font.size) return null
            val count = u16(font, cmapOffset + 2)
            var unicode: Map<Int, Int>? = null
            var unicodePriority = -1
            var symbol: Map<Int, Int> = emptyMap()
            var mac: Map<Int, Int> = emptyMap()
            for (i in 0 until count) {
                val rec = cmapOffset + 4 + i * 8
                if (rec + 8 > font.size) break
                val platform = u16(font, rec)
                val encoding = u16(font, rec + 2)
                val offset = cmapOffset + u32(font, rec + 4).toInt()
                val priority = when {
                    platform == 3 && encoding == 10 -> 3
                    platform == 3 && encoding == 1 -> 2
                    platform == 0 -> 1
                    else -> -1
                }
                if (priority > unicodePriority) {
                    parseSubtable(font, offset)?.let {
                        unicode = it
                        unicodePriority = priority
                    }
                } else if (platform == 3 && encoding == 0) {
                    symbol = parseSubtable(font, offset) ?: emptyMap()
                } else if (platform == 1 && encoding == 0) {
                    mac = parseSubtable(font, offset) ?: emptyMap()
                }
            }
            return TrueTypeCmap(unicode ?: emptyMap(), symbol, mac)
        }

        private fun parseSubtable(font: ByteArray, offset: Int): Map<Int, Int>? {
            if (offset + 6 > font.size) return null
            val map = HashMap<Int, Int>()
            when (u16(font, offset)) {
                0 -> for (c in 0 until 256) {
                    if (offset + 6 + c < font.size) map[c] = font[offset + 6 + c].toInt() and 0xFF
                }

                4 -> {
                    val segX2 = u16(font, offset + 6)
                    val endCodes = offset + 14
                    val startCodes = endCodes + segX2 + 2
                    val deltas = startCodes + segX2
                    val rangeOffsets = deltas + segX2
                    for (s in 0 until segX2 / 2) {
                        val end = u16(font, endCodes + s * 2)
                        val start = u16(font, startCodes + s * 2)
                        val delta = u16(font, deltas + s * 2)
                        val rangeOffsetPos = rangeOffsets + s * 2
                        val rangeOffset = u16(font, rangeOffsetPos)
                        if (start > end) continue
                        for (c in start..minOf(end, 0xFFFE)) {
                            val gid = if (rangeOffset == 0) {
                                (c + delta) and 0xFFFF
                            } else {
                                val pos = rangeOffsetPos + rangeOffset + (c - start) * 2
                                val glyph = if (pos + 2 > font.size) 0 else u16(font, pos)
                                if (glyph == 0) 0 else (glyph + delta) and 0xFFFF
                            }
                            if (gid != 0) map[c] = gid
                        }
                    }
                }

                6 -> {
                    val first = u16(font, offset + 6)
                    val count = u16(font, offset + 8)
                    for (i in 0 until count) {
                        val pos = offset + 10 + i * 2
                        if (pos + 2 > font.size) break
                        map[first + i] = u16(font, pos)
                    }
                }

                12 -> {
                    val groups = u32(font, offset + 12).toInt()
                    for (g in 0 until groups) {
                        val pos = offset + 16 + g * 12
                        if (pos + 12 > font.size) break
                        val start = u32(font, pos).toInt()
                        val end = u32(font, pos + 4).toInt()
                        val gid = u32(font, pos + 8).toInt()
                        if (end - start > 0x10FFFF || end < start) continue
                        for (c in start..end) map[c] = gid + (c - start)
                    }
                }

                else -> return null
            }
            return map
        }

        private fun u16(b: ByteArray, pos: Int): Int =
            ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, pos: Int): Long =
            ((b[pos].toLong() and 0xFF) shl 24) or ((b[pos + 1].toLong() and 0xFF) shl 16) or
                ((b[pos + 2].toLong() and 0xFF) shl 8) or (b[pos + 3].toLong() and 0xFF)
    }
}
