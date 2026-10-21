package me.rerere.document.pdf

/**
 * 一个字形：[unicode] 为空串表示无法映射；[width] 为文字空间中的宽度（已除以 1000）；
 * [wordSpace] 表示单字节编码 32，需要叠加字间距 Tw。
 */
internal class Glyph(val unicode: String, val width: Float, val wordSpace: Boolean)

internal abstract class PdfFont {
    open val vertical: Boolean = false

    abstract fun decode(bytes: ByteArray): List<Glyph>

    companion object {
        fun load(file: PdfFile, dict: PdfDict): PdfFont = try {
            when {
                (dict["Subtype"] as? PdfName)?.name == "Type0" -> Type0Font(file, dict)
                isMisencodedGbkFont(file, dict) -> GbkSimpleFont(file, dict)
                else -> SimpleFont(file, dict)
            }
        } catch (_: Exception) {
            FallbackFont
        }

        /**
         * 部分中文软件把宋体等中文字体写成 WinAnsiEncoding 的简单字体，实际正文却是 GBK 双字节编码。
         * 判断条件：未嵌入字体、没有 ToUnicode、编码为 WinAnsi 或缺省、字体名是中文字体。
         */
        private fun isMisencodedGbkFont(file: PdfFile, dict: PdfDict): Boolean {
            val subtype = (dict["Subtype"] as? PdfName)?.name
            if (subtype != "TrueType" && subtype != "Type1") return false
            if (dict["ToUnicode"] != null) return false
            val encoding = file.resolve(dict["Encoding"])
            if (encoding != null && (encoding as? PdfName)?.name != "WinAnsiEncoding") return false
            val descriptor = file.resolve(dict["FontDescriptor"]) as? PdfDict
            if (descriptor != null && (descriptor["FontFile"] != null || descriptor["FontFile2"] != null || descriptor["FontFile3"] != null)) return false
            val names = listOfNotNull(
                (dict["BaseFont"] as? PdfName)?.name,
                (descriptor?.get("FontName") as? PdfName)?.name,
                (descriptor?.get("FontName") as? PdfString)?.let { String(it.bytes, Charsets.ISO_8859_1) },
            )
            return names.any { name ->
                val decoded = if (name.all { it.code < 0x100 }) String(name.toByteArray(Charsets.ISO_8859_1), charset("GBK")) else name
                decoded.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } ||
                    CHINESE_FONT_NAMES.any { name.startsWith(it, ignoreCase = true) } || name.endsWith("_GB2312")
            }
        }

        private val CHINESE_FONT_NAMES = listOf("SimSun", "SimHei", "SimKai", "SimFang", "SimLi", "NSimSun", "KaiTi", "FangSong", "STSong", "STHeiti", "STKaiti", "STFangsong", "Microsoft YaHei", "MicrosoftYaHei")

        internal fun loadToUnicode(file: PdfFile, obj: PdfObject?): PdfCMap? {
            return when (val toUnicode = file.resolve(obj)) {
                is PdfStream -> file.decodeStream(toUnicode)?.let { runCatching { PdfCMap.parse(it) }.getOrNull() }
                    ?.takeIf { it.hasUnicode }

                is PdfName -> if (toUnicode.name.startsWith("Identity")) PdfCMap.predefined("UniGB-UCS2-H") else null
                else -> null
            }
        }

        internal fun fontFile(file: PdfFile, descriptor: PdfDict?, key: String): ByteArray? {
            val stream = file.resolve(descriptor?.get(key)) as? PdfStream ?: return null
            return file.decodeStream(stream)
        }
    }
}

private object FallbackFont : PdfFont() {
    override fun decode(bytes: ByteArray): List<Glyph> = bytes.map { b ->
        val c = b.toInt() and 0xFF
        Glyph(if (c in 32..126) c.toChar().toString() else "", 0.5f, c == 32)
    }
}

/** 按 GBK 解码的简单字体，见 [PdfFont.isMisencodedGbkFont] */
private class GbkSimpleFont(file: PdfFile, dict: PdfDict) : PdfFont() {
    private val cmap = PdfCMap.predefined("GBK-EUC-H")!!
    private val widths = FloatArray(256) { 0.5f }

    init {
        val firstChar = (file.resolve(dict["FirstChar"]) as? PdfNumber)?.int ?: 0
        val widthArray = file.resolve(dict["Widths"]) as? PdfArray
        if (widthArray != null) {
            for (code in 0 until 256) {
                (file.resolve(widthArray[code - firstChar]) as? PdfNumber)?.let { widths[code] = it.float / 1000f }
            }
        }
    }

    override fun decode(bytes: ByteArray): List<Glyph> {
        val glyphs = ArrayList<Glyph>(bytes.size)
        var pos = 0
        while (pos < bytes.size) {
            val len = cmap.codeLength(bytes, pos).coerceAtLeast(1)
            var code = 0L
            for (i in 0 until len) code = (code shl 8) or (bytes[pos + i].toLong() and 0xFF)
            val unicode = cmap.unicode(code, len, bytes, pos).orEmpty()
            glyphs.add(Glyph(unicode, if (len == 1) widths[code.toInt()] else 1f, len == 1 && code == 32L))
            pos += len
        }
        return glyphs
    }
}

/** Type1 / TrueType / Type3 等单字节字体 */
private class SimpleFont(file: PdfFile, dict: PdfDict) : PdfFont() {
    private val unicodeTable = arrayOfNulls<String>(256)
    private val widths = FloatArray(256)

    init {
        val subtype = (dict["Subtype"] as? PdfName)?.name
        val baseFont = (dict["BaseFont"] as? PdfName)?.name.orEmpty()
        val descriptor = file.resolve(dict["FontDescriptor"]) as? PdfDict
        val flags = (file.resolve(descriptor?.get("Flags")) as? PdfNumber)?.int ?: 0
        val symbolic = flags and 4 != 0
        val toUnicode = loadToUnicode(file, dict["ToUnicode"])
        val isType3 = subtype == "Type3"

        // 1. 基础编码
        val encodingObj = file.resolve(dict["Encoding"])
        val baseEncodingName = when (encodingObj) {
            is PdfName -> encodingObj.name
            is PdfDict -> (file.resolve(encodingObj["BaseEncoding"]) as? PdfName)?.name
            else -> null
        }
        val names = arrayOfNulls<String>(256)
        val type1File = if (subtype == "Type1" || subtype == "MMType1") fontFile(file, descriptor, "FontFile") else null
        val cff = if (type1File == null && (subtype == "Type1" || subtype == "MMType1")) {
            fontFile(file, descriptor, "FontFile3")?.let { CffFont.parse(it) }
        } else {
            null
        }
        val trueTypeFile = if (subtype == "TrueType") fontFile(file, descriptor, "FontFile2") else null
        val trueTypeCmap = trueTypeFile?.let { TrueTypeCmap.parse(it) }
        val stdName = baseFont.substringAfter('+')
        val base: CharArray? = baseEncodingName?.let { PdfEncodings.byName(it) } ?: when {
            stdName.startsWith("Symbol") -> PdfEncodings.SYMBOL
            stdName.startsWith("ZapfDingbats") -> PdfEncodings.ZAPF_DINGBATS
            type1File != null -> builtinType1Encoding(type1File, names)
            cff != null -> cff.encoding?.let { builtin ->
                for (code in 0 until 256) if (names[code] == null) names[code] = builtin[code]
                null
            } ?: PdfEncodings.STANDARD
            isType3 -> null
            subtype == "TrueType" && (symbolic || trueTypeCmap != null) && encodingObj == null -> null
            subtype == "TrueType" -> PdfEncodings.WIN_ANSI
            else -> PdfEncodings.STANDARD
        }

        // 2. Differences
        if (encodingObj is PdfDict) {
            val diffs = file.resolve(encodingObj["Differences"]) as? PdfArray
            var code = 0
            diffs?.items?.forEach { item ->
                when (val v = file.resolve(item)) {
                    is PdfNumber -> code = v.int
                    is PdfName -> {
                        if (code in 0..255) names[code] = v.name
                        code++
                    }

                    else -> {}
                }
            }
        }

        for (code in 0 until 256) {
            val bytes = byteArrayOf(code.toByte())
            // 有些生成器给单字节字体写 2 字节的 ToUnicode 编码（如 <0041>）
            val mapped = toUnicode?.unicode(code.toLong(), 1, bytes, 0)?.meaningful()
                ?: toUnicode?.takeIf { !it.hasCharset }?.unicode(code.toLong(), 2, bytes, 0)?.meaningful()
            mapped?.let {
                unicodeTable[code] = it
                continue
            }
            val name = names[code]
            if (name != null) {
                // 字形名已知但无法映射（如 TeX 数学字体的专用字形）时宁可留空，不要猜成 ASCII；
                // Type3 字体常用 /a65、/g65 之类按编码命名的字形，仍按编码退回
                unicodeTable[code] = PdfEncodings.glyphToUnicode(name)
                    ?: trueTypeCmap?.let { trueTypeUnicode(it, code, symbolic) }
                    ?: if (isType3) type3NameChar(name) ?: fallbackChar(code) else null
                continue
            }
            unicodeTable[code] = base?.get(code)?.takeIf { it != '\u0000' }?.toString()
                ?: trueTypeCmap?.let { trueTypeUnicode(it, code, symbolic) }
                ?: if (subtype == "TrueType") winAnsiChar(code) else fallbackChar(code)
        }

        // 宽度
        val firstChar = (file.resolve(dict["FirstChar"]) as? PdfNumber)?.int ?: 0
        val widthArray = file.resolve(dict["Widths"]) as? PdfArray
        val missingWidth = (file.resolve(descriptor?.get("MissingWidth")) as? PdfNumber)?.float ?: 0f
        val scale = if (isType3) {
            val matrix = file.resolve(dict["FontMatrix"]) as? PdfArray
            ((matrix?.get(0) as? PdfNumber)?.float ?: 0.001f)
        } else {
            0.001f
        }
        val standard = if (isType3) null else StandardFontMetrics.widths(baseFont)
        for (code in 0 until 256) {
            val w = widthArray?.let { (file.resolve(it[code - firstChar]) as? PdfNumber)?.float }
            widths[code] = when {
                w != null -> w * scale
                // 没有 Widths（标准 14 字体）或缺少该编码时，用标准字体字宽
                standard != null -> unicodeTable[code]?.codePointAt(0)?.let { standard[it] }
                    ?: if (missingWidth > 0f) missingWidth * scale else estimateWidth(unicodeTable[code])
                widthArray != null -> missingWidth * scale
                else -> estimateWidth(unicodeTable[code])
            }
        }
    }

    // 未知字体又没有 Widths 时按字符类别粗估
    private fun estimateWidth(text: String?): Float {
        val c = text?.firstOrNull() ?: ' '
        return when {
            c == ' ' -> 0.25f
            c.isUpperCase() -> 0.667f
            else -> 0.5f
        }
    }

    override fun decode(bytes: ByteArray): List<Glyph> = bytes.map { b ->
        val code = b.toInt() and 0xFF
        Glyph(unicodeTable[code].orEmpty(), widths[code], code == 32)
    }

    private fun trueTypeUnicode(cmap: TrueTypeCmap, code: Int, symbolic: Boolean): String? {
        // 符号 TrueType：(3,0) 子表常以 0xF000 + code 编址
        val gid = cmap.symbol[0xF000 or code] ?: cmap.symbol[code] ?: cmap.mac[code]
        if (gid != null) {
            cmap.unicodeOfGlyph(gid)?.let { return it }
            if (symbolic && code in 32..126) return code.toChar().toString()
        }
        return cmap.unicode[code]?.let { code.toChar().toString() }
    }

    // 解析 Type1 字体程序明文部分中的内置编码
    private fun builtinType1Encoding(font: ByteArray, names: Array<String?>): CharArray? {
        val head = String(font, 0, minOf(font.size, 64 * 1024), Charsets.ISO_8859_1)
        val encodingPos = head.indexOf("/Encoding")
        if (encodingPos < 0) return PdfEncodings.STANDARD
        if (head.startsWith("StandardEncoding", encodingPos + 10) || head.indexOf("StandardEncoding def", encodingPos) in encodingPos..encodingPos + 40) {
            return PdfEncodings.STANDARD
        }
        val end = head.indexOf("readonly def", encodingPos).let { if (it < 0) head.indexOf("eexec", encodingPos) else it }
        val section = if (end > encodingPos) head.substring(encodingPos, end) else head.substring(encodingPos)
        DUP_REGEX.findAll(section).forEach { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (code in 0..255 && names[code] == null) names[code] = m.groupValues[2]
        }
        return null
    }

    // 没有编码信息的 TrueType 字体按 WinAnsi 解释
    private fun winAnsiChar(code: Int): String? =
        if (code >= 32) PdfEncodings.WIN_ANSI[code].takeIf { it != '\u0000' }?.toString() else null

    // dvips 等生成的 Type3 字体用 /a65、/a192 这样的名字，数字即字符编码（按 WinAnsi 解释）
    private fun type3NameChar(name: String): String? {
        val code = TYPE3_NAME_REGEX.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (code !in 32..255) return null
        return PdfEncodings.WIN_ANSI[code].takeIf { it != '\u0000' }?.toString()
    }

    private fun fallbackChar(code: Int): String? = if (code in 33..126) code.toChar().toString() else if (code == 32) " " else null

    companion object {
        private val TYPE3_NAME_REGEX = Regex("""[A-Za-z]{1,3}(\d{1,3})""")
        private val DUP_REGEX = Regex("""dup\s+(\d+)\s*/([^\s/\[\]{}()<>]+)\s+put""")
    }
}

/** 复合字体 */
private class Type0Font(private val file: PdfFile, dict: PdfDict) : PdfFont() {
    private val encoding: PdfCMap
    private val toUnicode: PdfCMap?
    private val defaultWidth: Float
    private val cidWidths = HashMap<Int, Float>()
    private val cidToGid: ByteArray?
    private val trueTypeCmap: TrueTypeCmap?
    private val ordering: String?
    private val cff: CffFont?
    private val cache = HashMap<Long, Glyph>()

    override val vertical: Boolean get() = encoding.vertical

    init {
        encoding = when (val enc = file.resolve(dict["Encoding"])) {
            is PdfName -> PdfCMap.predefined(enc.name) ?: PdfCMap.predefined("Identity-H")!!
            is PdfStream -> file.decodeStream(enc)?.let { runCatching { PdfCMap.parse(it) }.getOrNull() }
                ?: PdfCMap.predefined("Identity-H")!!

            else -> PdfCMap.predefined("Identity-H")!!
        }
        toUnicode = loadToUnicode(file, dict["ToUnicode"])
        val descendant = (file.resolve(dict["DescendantFonts"]) as? PdfArray)?.get(0)?.let { file.resolve(it) } as? PdfDict
        defaultWidth = ((file.resolve(descendant?.get("DW")) as? PdfNumber)?.float ?: 1000f) / 1000f
        (file.resolve(descendant?.get("W")) as? PdfArray)?.let { parseWidths(it) }
        cidToGid = (file.resolve(descendant?.get("CIDToGIDMap")) as? PdfStream)?.let { file.decodeStream(it) }
        val descriptor = file.resolve(descendant?.get("FontDescriptor")) as? PdfDict
        val systemInfo = file.resolve(descendant?.get("CIDSystemInfo")) as? PdfDict
        ordering = (file.resolve(systemInfo?.get("Ordering")) as? PdfString)?.text()
            ?.takeIf { (file.resolve(systemInfo?.get("Registry")) as? PdfString)?.text() == "Adobe" && CidToUnicode.supports(it) }
        val needGlyphLookup = toUnicode == null && !encoding.hasCharset && ordering == null
        trueTypeCmap = if (needGlyphLookup) fontFile(file, descriptor, "FontFile2")?.let { TrueTypeCmap.parse(it) } else null
        cff = if (needGlyphLookup) fontFile(file, descriptor, "FontFile3")?.let { CffFont.parse(it) }?.takeIf { !it.isCidKeyed } else null
    }

    private fun parseWidths(w: PdfArray) {
        var i = 0
        val items = w.items
        while (i < items.size) {
            val first = (file.resolve(items[i]) as? PdfNumber)?.int ?: break
            val next = file.resolve(items.getOrNull(i + 1)) ?: break
            if (next is PdfArray) {
                next.items.forEachIndexed { k, v ->
                    (file.resolve(v) as? PdfNumber)?.let { cidWidths[first + k] = it.float / 1000f }
                }
                i += 2
            } else {
                val last = (next as? PdfNumber)?.int ?: break
                val width = (file.resolve(items.getOrNull(i + 2)) as? PdfNumber)?.float ?: break
                if (last - first in 0..65535) for (cid in first..last) cidWidths[cid] = width / 1000f
                i += 3
            }
        }
    }

    override fun decode(bytes: ByteArray): List<Glyph> {
        val glyphs = ArrayList<Glyph>(bytes.size / 2 + 1)
        var pos = 0
        while (pos < bytes.size) {
            val len = encoding.codeLength(bytes, pos).coerceAtLeast(1)
            var code = 0L
            for (i in 0 until len) code = (code shl 8) or (bytes[pos + i].toLong() and 0xFF)
            val key = (len.toLong() shl 40) or code
            val glyph = cache.getOrPut(key) { glyphOf(code, len, bytes, pos) }
            glyphs.add(glyph)
            pos += len
        }
        return glyphs
    }

    private fun glyphOf(code: Long, len: Int, bytes: ByteArray, pos: Int): Glyph {
        // 按字符集解码的预定义 CMap（如 UniJIS-UTF16-H）没有 CID，用 Unicode 反查出 CID 以取得字宽
        val cid = encoding.cid(code, len) ?: ordering?.takeIf { encoding.hasCharset }?.let { ordering ->
            encoding.unicode(code, len, bytes, pos)
                ?.takeIf { it.codePointCount(0, it.length) == 1 }
                ?.let { CidToUnicode.cidOf(ordering, it.codePointAt(0)) }
        }
        val unicode = toUnicode?.unicode(code, len, bytes, pos)?.meaningful()
            ?: encoding.unicode(code, len, bytes, pos)
            ?: cid?.let { glyphUnicode(it) }
            ?: ""
        // 预定义 CMap 的单字节编码对应 Adobe 字符集中的半角字形
        val width = cid?.let { cidWidths[it] } ?: if (len == 1 && encoding.hasCharset) 0.5f else defaultWidth
        return Glyph(unicode, width, len == 1 && code == 32L)
    }

    private fun glyphUnicode(cid: Int): String? {
        ordering?.let { return CidToUnicode.lookup(it, cid) }
        // 非 CID-keyed 的 CFF：CID 即 GID，用字形名还原
        cff?.let { font -> return font.glyphNames.getOrNull(cid)?.let { PdfEncodings.glyphToUnicode(it) } }
        val cmap = trueTypeCmap ?: return null
        val gid = cidToGid?.let { map ->
            if (cid * 2 + 1 < map.size) ((map[cid * 2].toInt() and 0xFF) shl 8) or (map[cid * 2 + 1].toInt() and 0xFF) else return null
        } ?: cid
        return cmap.unicodeOfGlyph(gid)
    }
}

// 有些生成器在 ToUnicode 里把字形映射到 U+0000，这种映射当作不存在，改用字形名等其他来源
private fun String.meaningful(): String? = takeUnless { it.all { c -> c == '\u0000' } }
