package me.rerere.document.pdf

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CMap：既用于 ToUnicode（code → Unicode），也用于 Type0 字体编码（字节切分 + code → CID）。
 * 预定义 CMap 不携带 CID 表，而是直接用对应的 Java 字符集把字节解码成 Unicode。
 */
internal class PdfCMap {
    private class Codespace(val length: Int, val low: ByteArray, val high: ByteArray)
    private class CidRange(val length: Int, val low: Long, val high: Long, val cid: Int)
    private class UnicodeRange(val length: Int, val low: Long, val high: Long, val base: String?, val list: List<String>?)

    private val codespaces = ArrayList<Codespace>()
    private val unicodeMap = HashMap<Long, String>()
    private val unicodeRanges = ArrayList<UnicodeRange>()
    private val cidMap = HashMap<Long, Int>()
    private val cidRanges = ArrayList<CidRange>()

    var vertical = false
        private set

    // 预定义 CMap 的行为
    private var identity = false
    private var charset: Charset? = null
    private var gl94 = false // 两字节 GL 区编码（如 JIS X 0208 原码），解码前每字节加 0x80
    // 预定义 CMap 是全局共享的，缓存可能被多个线程同时写入
    private val charsetCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    val hasCharset: Boolean get() = charset != null
    val hasUnicode: Boolean get() = unicodeMap.isNotEmpty() || unicodeRanges.isNotEmpty() || charset != null

    /** 返回从 [pos] 开始的下一个字符编码的字节长度 */
    fun codeLength(bytes: ByteArray, pos: Int): Int {
        val remaining = bytes.size - pos
        if (codespaces.isEmpty()) return minOf(if (identity) 2 else 1, remaining)
        for (len in 1..4) {
            if (len > remaining) break
            for (cs in codespaces) {
                if (cs.length != len) continue
                var ok = true
                for (i in 0 until len) {
                    val b = bytes[pos + i].toInt() and 0xFF
                    if (b < (cs.low[i].toInt() and 0xFF) || b > (cs.high[i].toInt() and 0xFF)) {
                        ok = false
                        break
                    }
                }
                if (ok) return len
            }
        }
        // 不在任何 codespace 中：按能匹配首字节的最短长度消费，否则一个字节
        for (cs in codespaces.sortedBy { it.length }) {
            val b = bytes[pos].toInt() and 0xFF
            if (b >= (cs.low[0].toInt() and 0xFF) && b <= (cs.high[0].toInt() and 0xFF)) return minOf(cs.length, remaining)
        }
        return minOf(codespaces.minOf { it.length }, remaining)
    }

    fun cid(code: Long, length: Int): Int? {
        if (identity) return code.toInt()
        cidMap[key(code, length)]?.let { return it }
        // 范围重叠时后定义的优先（有些生成器会写出覆盖过宽的范围）
        for (r in cidRanges.asReversed()) {
            if (r.length == length && code >= r.low && code <= r.high) return r.cid + (code - r.low).toInt()
        }
        return null
    }

    fun unicode(code: Long, length: Int, bytes: ByteArray, pos: Int): String? {
        unicodeMap[key(code, length)]?.let { return it }
        for (r in unicodeRanges.asReversed()) {
            if (r.length != length || code < r.low || code > r.high) continue
            val offset = (code - r.low).toInt()
            if (r.list != null) return r.list.getOrNull(offset)
            val base = r.base ?: return null
            if (base.isEmpty()) return null
            // 目标字符串最后一个字符递增
            val last = base.codePointBefore(base.length)
            val prefix = base.substring(0, base.length - Character.charCount(last))
            val cp = last + offset
            return if (Character.isValidCodePoint(cp)) prefix + String(Character.toChars(cp)) else null
        }
        val cs = charset ?: return null
        val k = key(code, length)
        charsetCache[k]?.let { return it }
        val raw = bytes.copyOfRange(pos, pos + length)
        if (gl94 && length == 2) for (i in raw.indices) raw[i] = (raw[i].toInt() or 0x80).toByte()
        val decoded = try {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Exception) {
            ""
        }
        charsetCache[k] = decoded
        return decoded.ifEmpty { null }
    }

    private fun key(code: Long, length: Int): Long = (length.toLong() shl 40) or code

    // 仅在 [base] 没有自己的映射时继承 usecmap 的父 CMap
    private fun inherit(parent: PdfCMap) {
        if (codespaces.isEmpty()) codespaces.addAll(parent.codespaces)
        for ((k, v) in parent.unicodeMap) unicodeMap.putIfAbsent(k, v)
        // 父 CMap 的范围优先级低于自身定义（查找时倒序）
        unicodeRanges.addAll(0, parent.unicodeRanges)
        for ((k, v) in parent.cidMap) cidMap.putIfAbsent(k, v)
        cidRanges.addAll(0, parent.cidRanges)
        if (parent.identity) identity = true
        if (charset == null) {
            charset = parent.charset
            gl94 = parent.gl94
        }
        if (parent.vertical) vertical = true
    }

    companion object {
        fun parse(data: ByteArray): PdfCMap {
            val cmap = PdfCMap()
            val lexer = PdfLexer(data)
            val operands = ArrayList<PdfObject>()
            var mode: String? = null
            while (true) {
                val obj = lexer.readObject(contentStream = true) ?: break
                if (obj !is PdfOperator) {
                    operands.add(obj)
                    continue
                }
                when (obj.name) {
                    "begincodespacerange", "beginbfchar", "beginbfrange", "begincidchar", "begincidrange", "beginnotdefrange", "beginnotdefchar" -> {
                        // 丢掉 begin 前的条目数
                        operands.clear()
                        mode = obj.name
                    }
                    "endcodespacerange" -> {
                        operands.chunked(2).forEach { pair ->
                            val low = (pair.getOrNull(0) as? PdfString)?.bytes ?: return@forEach
                            val high = (pair.getOrNull(1) as? PdfString)?.bytes ?: return@forEach
                            if (low.size == high.size && low.size in 1..4) cmap.codespaces.add(Codespace(low.size, low, high))
                        }
                        mode = null
                    }

                    "endbfchar" -> {
                        operands.chunked(2).forEach { pair ->
                            val src = pair.getOrNull(0) as? PdfString ?: return@forEach
                            val dst = destination(pair.getOrNull(1)) ?: return@forEach
                            if (src.bytes.size in 1..4) cmap.unicodeMap[cmap.key(toLong(src.bytes), src.bytes.size)] = dst
                        }
                        mode = null
                    }

                    "endbfrange" -> {
                        operands.chunked(3).forEach { triple ->
                            val low = (triple.getOrNull(0) as? PdfString)?.bytes ?: return@forEach
                            val high = (triple.getOrNull(1) as? PdfString)?.bytes ?: return@forEach
                            if (low.size !in 1..4 || high.size != low.size) return@forEach
                            val lo = toLong(low)
                            val hi = toLong(high)
                            if (hi < lo) return@forEach
                            when (val dst = triple.getOrNull(2)) {
                                is PdfArray -> cmap.unicodeRanges.add(
                                    UnicodeRange(low.size, lo, hi, null, dst.items.map { destination(it) ?: "" })
                                )

                                else -> cmap.unicodeRanges.add(UnicodeRange(low.size, lo, hi, destination(dst), null))
                            }
                        }
                        mode = null
                    }

                    "endcidchar" -> {
                        operands.chunked(2).forEach { pair ->
                            val src = pair.getOrNull(0) as? PdfString ?: return@forEach
                            val cid = (pair.getOrNull(1) as? PdfNumber)?.int ?: return@forEach
                            if (src.bytes.size in 1..4) cmap.cidMap[cmap.key(toLong(src.bytes), src.bytes.size)] = cid
                        }
                        mode = null
                    }

                    "endcidrange" -> {
                        operands.chunked(3).forEach { triple ->
                            val low = (triple.getOrNull(0) as? PdfString)?.bytes ?: return@forEach
                            val high = (triple.getOrNull(1) as? PdfString)?.bytes ?: return@forEach
                            val cid = (triple.getOrNull(2) as? PdfNumber)?.int ?: return@forEach
                            if (low.size in 1..4 && high.size == low.size) {
                                cmap.cidRanges.add(CidRange(low.size, toLong(low), toLong(high), cid))
                            }
                        }
                        mode = null
                    }

                    "endnotdefrange", "endnotdefchar" -> mode = null
                    "usecmap" -> {
                        val name = (operands.lastOrNull() as? PdfName)?.name
                        if (name != null) predefined(name)?.let { cmap.inherit(it) }
                    }

                    "def" -> {
                        if (operands.size >= 2) {
                            val key = (operands[operands.size - 2] as? PdfName)?.name
                            val value = operands.last()
                            if (key == "WMode" && (value as? PdfNumber)?.int == 1) cmap.vertical = true
                        }
                    }
                }
                if (mode == null) operands.clear()
            }
            return cmap
        }

        private fun destination(obj: PdfObject?): String? = when (obj) {
            is PdfString -> {
                val b = obj.bytes
                when {
                    b.isEmpty() -> ""
                    b.size == 1 -> (b[0].toInt() and 0xFF).toChar().toString()
                    b.size % 2 == 1 -> String(b, 1, b.size - 1, Charsets.UTF_16BE)
                    else -> String(b, Charsets.UTF_16BE)
                }
            }

            is PdfName -> PdfEncodings.glyphToUnicode(obj.name)
            is PdfNumber -> obj.int.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
            else -> null
        }

        private fun toLong(bytes: ByteArray): Long {
            var v = 0L
            for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
            return v
        }

        private val predefinedCache = HashMap<String, PdfCMap?>()

        /**
         * 预定义 CMap。只定义字节切分方式，Unicode 由对应字符集直接解码。
         * 不认识的名字返回 null。
         */
        @Synchronized
        fun predefined(name: String): PdfCMap? = predefinedCache.getOrPut(name) { createPredefined(name) }

        private fun createPredefined(name: String): PdfCMap? {
            val cmap = PdfCMap()
            cmap.vertical = name.endsWith("-V")
            val base = name.removeSuffix("-H").removeSuffix("-V")
            if (base == "Identity" || name == "Identity") {
                cmap.identity = true
                cmap.addCodespace("0000", "FFFF")
                return cmap
            }
            if (base.startsWith("Uni")) {
                when {
                    "UCS2" in base -> {
                        cmap.addCodespace("0000", "FFFF")
                        cmap.charset = Charsets.UTF_16BE
                    }

                    "UTF16" in base -> {
                        cmap.addCodespace("0000", "D7FF")
                        cmap.addCodespace("D800DC00", "DBFFDFFF")
                        cmap.addCodespace("E000", "FFFF")
                        cmap.charset = Charsets.UTF_16BE
                    }

                    "UTF8" in base -> {
                        cmap.addCodespace("00", "7F")
                        cmap.addCodespace("C080", "DFBF")
                        cmap.addCodespace("E08080", "EFBFBF")
                        cmap.addCodespace("F0808080", "F7BFBFBF")
                        cmap.charset = Charsets.UTF_8
                    }

                    "UTF32" in base -> {
                        cmap.addCodespace("00000000", "0010FFFF")
                        cmap.charset = charsetOf("UTF-32BE")
                    }

                    else -> return null
                }
                return cmap
            }
            when (base) {
                "GB-EUC", "GBpc-EUC", "GBT-EUC" -> cmap.euc("GB2312", "EUC-CN")
                "GBK-EUC", "GBKp-EUC" -> cmap.doubleByte("8140", "GBK")
                "GBK2K" -> {
                    cmap.doubleByte("8140", "GB18030")
                    cmap.addCodespace("81308130", "FE39FE39")
                }

                "B5pc", "ETen-B5", "ETenms-B5", "B5" -> cmap.doubleByte("8140", "x-windows-950", "Big5")
                "HKscs-B5", "HKdla-B5", "HKdlb-B5", "HKgccs-B5", "HKm314-B5", "HKm471-B5" -> cmap.doubleByte("8140", "Big5-HKSCS", "Big5")
                "CNS-EUC" -> cmap.euc("x-EUC-TW", "EUC-TW")
                "KSC-EUC", "KSCpc-EUC" -> cmap.euc("EUC-KR")
                "KSCms-UHC", "KSCms-UHC-HW" -> cmap.doubleByte("8141", "x-windows-949", "windows-949", "MS949", "EUC-KR")
                "83pv-RKSJ", "90ms-RKSJ", "90msp-RKSJ", "90pv-RKSJ", "Add-RKSJ", "Ext-RKSJ" -> {
                    cmap.addCodespace("00", "80")
                    cmap.addCodespace("A0", "DF")
                    cmap.addCodespace("8140", "9FFC")
                    cmap.addCodespace("E040", "FCFC")
                    cmap.charset = charsetOf("windows-31j", "Shift_JIS")
                }

                "EUC" -> {
                    cmap.addCodespace("00", "80")
                    cmap.addCodespace("8EA0", "8EDF")
                    cmap.addCodespace("A1A1", "FEFE")
                    cmap.charset = charsetOf("EUC-JP")
                }

                "", "Add", "Ext", "NWP", "Hankaku", "Hiragana", "Katakana", "Roman", "WP-Symbol" -> cmap.gl94("EUC-JP")
                "GB", "GBT" -> cmap.gl94("GB2312", "EUC-CN")
                "KSC" -> cmap.gl94("EUC-KR")
                else -> return null
            }
            return cmap
        }

        private fun PdfCMap.addCodespace(low: String, high: String) {
            codespaces.add(Codespace(low.length / 2, hexBytes(low), hexBytes(high)))
        }

        private fun PdfCMap.euc(vararg charsets: String) {
            addCodespace("00", "80")
            addCodespace("A1A1", "FEFE")
            charset = charsetOf(*charsets)
        }

        private fun PdfCMap.doubleByte(low: String, vararg charsets: String) {
            addCodespace("00", "80")
            addCodespace(low, "FEFE")
            charset = charsetOf(*charsets)
        }

        private fun PdfCMap.gl94(vararg charsets: String) {
            addCodespace("2121", "7E7E")
            charset = charsetOf(*charsets)
            gl94 = true
        }

        private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun charsetOf(vararg names: String): Charset? {
            for (n in names) {
                try {
                    if (Charset.isSupported(n)) return Charset.forName(n)
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
