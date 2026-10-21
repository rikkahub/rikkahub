package me.rerere.document.pdf

/**
 * 最小 CFF 解析：只读取内置编码（code → 字形名）和字形名（GID → 名字），
 * 用于 FontFile3 字体没有 ToUnicode、也没有 Differences 时还原字符。
 */
internal class CffFont private constructor(
    // 内置编码，null 表示使用 StandardEncoding
    val encoding: Array<String?>?,
    // GID → 字形名；CID-keyed 字体为空
    val glyphNames: Array<String?>,
    val isCidKeyed: Boolean,
) {
    companion object {
        fun parse(data: ByteArray): CffFont? = try {
            Parser(data).parse()
        } catch (_: Exception) {
            null
        }

        fun sidName(sid: Int, strings: List<ByteArray>): String? = when {
            sid < STANDARD_STRINGS.size -> STANDARD_STRINGS[sid]
            sid - STANDARD_STRINGS.size < strings.size -> String(strings[sid - STANDARD_STRINGS.size], Charsets.ISO_8859_1)
            else -> null
        }

        // CFF 规范附录 A 的标准字符串（SID 0-390），由 fontTools.cffLib 生成
        private val STANDARD_STRINGS: List<String> = (
            ".notdef space exclam quotedbl numbersign dollar percent ampersand quoteright parenleft parenright " +
            "asterisk plus comma hyphen period slash zero one two three four five six seven eight nine colon " +
            "semicolon less equal greater question at A B C D E F G H I J K L M N O P Q R S T U V W X Y Z " +
            "bracketleft backslash bracketright asciicircum underscore quoteleft a b c d e f g h i j k l m n o p " +
            "q r s t u v w x y z braceleft bar braceright asciitilde exclamdown cent sterling fraction yen florin " +
            "section currency quotesingle quotedblleft guillemotleft guilsinglleft guilsinglright fi fl endash " +
            "dagger daggerdbl periodcentered paragraph bullet quotesinglbase quotedblbase quotedblright " +
            "guillemotright ellipsis perthousand questiondown grave acute circumflex tilde macron breve dotaccent " +
            "dieresis ring cedilla hungarumlaut ogonek caron emdash AE ordfeminine Lslash Oslash OE ordmasculine " +
            "ae dotlessi lslash oslash oe germandbls onesuperior logicalnot mu trademark Eth onehalf plusminus " +
            "Thorn onequarter divide brokenbar degree thorn threequarters twosuperior registered minus eth " +
            "multiply threesuperior copyright Aacute Acircumflex Adieresis Agrave Aring Atilde Ccedilla Eacute " +
            "Ecircumflex Edieresis Egrave Iacute Icircumflex Idieresis Igrave Ntilde Oacute Ocircumflex Odieresis " +
            "Ograve Otilde Scaron Uacute Ucircumflex Udieresis Ugrave Yacute Ydieresis Zcaron aacute acircumflex " +
            "adieresis agrave aring atilde ccedilla eacute ecircumflex edieresis egrave iacute icircumflex " +
            "idieresis igrave ntilde oacute ocircumflex odieresis ograve otilde scaron uacute ucircumflex " +
            "udieresis ugrave yacute ydieresis zcaron exclamsmall Hungarumlautsmall dollaroldstyle dollarsuperior " +
            "ampersandsmall Acutesmall parenleftsuperior parenrightsuperior twodotenleader onedotenleader " +
            "zerooldstyle oneoldstyle twooldstyle threeoldstyle fouroldstyle fiveoldstyle sixoldstyle " +
            "sevenoldstyle eightoldstyle nineoldstyle commasuperior threequartersemdash periodsuperior " +
            "questionsmall asuperior bsuperior centsuperior dsuperior esuperior isuperior lsuperior msuperior " +
            "nsuperior osuperior rsuperior ssuperior tsuperior ff ffi ffl parenleftinferior parenrightinferior " +
            "Circumflexsmall hyphensuperior Gravesmall Asmall Bsmall Csmall Dsmall Esmall Fsmall Gsmall Hsmall " +
            "Ismall Jsmall Ksmall Lsmall Msmall Nsmall Osmall Psmall Qsmall Rsmall Ssmall Tsmall Usmall Vsmall " +
            "Wsmall Xsmall Ysmall Zsmall colonmonetary onefitted rupiah Tildesmall exclamdownsmall centoldstyle " +
            "Lslashsmall Scaronsmall Zcaronsmall Dieresissmall Brevesmall Caronsmall Dotaccentsmall Macronsmall " +
            "figuredash hypheninferior Ogoneksmall Ringsmall Cedillasmall questiondownsmall oneeighth " +
            "threeeighths fiveeighths seveneighths onethird twothirds zerosuperior foursuperior fivesuperior " +
            "sixsuperior sevensuperior eightsuperior ninesuperior zeroinferior oneinferior twoinferior " +
            "threeinferior fourinferior fiveinferior sixinferior seveninferior eightinferior nineinferior " +
            "centinferior dollarinferior periodinferior commainferior Agravesmall Aacutesmall Acircumflexsmall " +
            "Atildesmall Adieresissmall Aringsmall AEsmall Ccedillasmall Egravesmall Eacutesmall Ecircumflexsmall " +
            "Edieresissmall Igravesmall Iacutesmall Icircumflexsmall Idieresissmall Ethsmall Ntildesmall " +
            "Ogravesmall Oacutesmall Ocircumflexsmall Otildesmall Odieresissmall OEsmall Oslashsmall Ugravesmall " +
            "Uacutesmall Ucircumflexsmall Udieresissmall Yacutesmall Thornsmall Ydieresissmall 001.000 001.001 " +
            "001.002 001.003 Black Bold Book Light Medium Regular Roman Semibold"
            ).split(' ')
    }

    private class Parser(private val d: ByteArray) {
        private fun u8(p: Int) = d[p].toInt() and 0xFF
        private fun u16(p: Int) = (u8(p) shl 8) or u8(p + 1)
        private fun offset(p: Int, size: Int): Int {
            var v = 0
            for (i in 0 until size) v = (v shl 8) or u8(p + i)
            return v
        }

        // 读取 INDEX，返回各项字节和 INDEX 之后的位置
        private fun index(start: Int): Pair<List<ByteArray>, Int> {
            val count = u16(start)
            if (count == 0) return emptyList<ByteArray>() to start + 2
            val offSize = u8(start + 2)
            val offsets = IntArray(count + 1) { offset(start + 3 + it * offSize, offSize) }
            val dataStart = start + 3 + (count + 1) * offSize - 1
            val items = List(count) { d.copyOfRange(dataStart + offsets[it], dataStart + offsets[it + 1]) }
            return items to dataStart + offsets[count]
        }

        // 解析 Top DICT，返回 operator → 操作数
        private fun dict(data: ByteArray): Map<Int, List<Double>> {
            val result = HashMap<Int, List<Double>>()
            val operands = ArrayList<Double>()
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                when {
                    b0 <= 21 -> {
                        val op = if (b0 == 12) {
                            i++
                            1200 + (data[i].toInt() and 0xFF)
                        } else {
                            b0
                        }
                        result[op] = operands.toList()
                        operands.clear()
                        i++
                    }

                    b0 == 28 -> {
                        operands.add((((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)).toShort().toDouble())
                        i += 3
                    }

                    b0 == 29 -> {
                        var v = 0
                        for (k in 1..4) v = (v shl 8) or (data[i + k].toInt() and 0xFF)
                        operands.add(v.toDouble())
                        i += 5
                    }

                    b0 == 30 -> {
                        // 实数：跳过半字节直到结束标记 0xF
                        i++
                        while (i < data.size) {
                            val b = data[i].toInt() and 0xFF
                            i++
                            if (b and 0x0F == 0x0F || b shr 4 == 0x0F) break
                        }
                        operands.add(0.0)
                    }

                    b0 in 32..246 -> {
                        operands.add((b0 - 139).toDouble())
                        i++
                    }

                    b0 in 247..250 -> {
                        operands.add(((b0 - 247) * 256 + (data[i + 1].toInt() and 0xFF) + 108).toDouble())
                        i += 2
                    }

                    b0 in 251..254 -> {
                        operands.add((-(b0 - 251) * 256 - (data[i + 1].toInt() and 0xFF) - 108).toDouble())
                        i += 2
                    }

                    else -> i++
                }
            }
            return result
        }

        fun parse(): CffFont? {
            if (d.size < 4) return null
            val (_, afterName) = index(u8(2))
            val (topDicts, afterTop) = index(afterName)
            val (strings, _) = index(afterTop)
            val top = dict(topDicts.firstOrNull() ?: return null)
            val isCid = top.containsKey(1230) // ROS
            val charStrings = top[17]?.firstOrNull()?.toInt() ?: return null
            val nGlyphs = u16(charStrings)
            val glyphSids = IntArray(nGlyphs)
            val charsetOffset = top[15]?.firstOrNull()?.toInt() ?: 0
            if (charsetOffset > 2) {
                var p = charsetOffset + 1
                var gid = 1
                when (u8(charsetOffset)) {
                    0 -> while (gid < nGlyphs) {
                        glyphSids[gid++] = u16(p)
                        p += 2
                    }

                    1, 2 -> {
                        val wide = u8(charsetOffset) == 2
                        while (gid < nGlyphs) {
                            val first = u16(p)
                            val left = if (wide) u16(p + 2) else u8(p + 2)
                            p += if (wide) 4 else 3
                            for (k in 0..left) if (gid < nGlyphs) glyphSids[gid++] = first + k
                        }
                    }
                }
            } else if (charsetOffset == 0) {
                // ISOAdobe：GID 与 SID 相同
                for (gid in 0 until minOf(nGlyphs, 229)) glyphSids[gid] = gid
            }
            val glyphNames = if (isCid) arrayOfNulls(0) else Array(nGlyphs) { if (it == 0) null else sidName(glyphSids[it], strings) }

            val encodingOffset = top[16]?.firstOrNull()?.toInt() ?: 0
            val encoding = if (isCid || encodingOffset <= 1) {
                null
            } else {
                val names = arrayOfNulls<String>(256)
                val format = u8(encodingOffset)
                var p = encodingOffset + 1
                when (format and 0x7F) {
                    0 -> {
                        val n = u8(p)
                        p++
                        for (gid in 1..n) {
                            names[u8(p)] = glyphNames.getOrNull(gid)
                            p++
                        }
                    }

                    1 -> {
                        val ranges = u8(p)
                        p++
                        var gid = 1
                        repeat(ranges) {
                            val first = u8(p)
                            val left = u8(p + 1)
                            p += 2
                            for (k in 0..left) {
                                if (first + k < 256) names[first + k] = glyphNames.getOrNull(gid)
                                gid++
                            }
                        }
                    }
                }
                if (format and 0x80 != 0) {
                    val sups = u8(p)
                    p++
                    repeat(sups) {
                        names[u8(p)] = sidName(u16(p + 1), strings)
                        p += 3
                    }
                }
                names
            }
            return CffFont(encoding, glyphNames, isCid)
        }
    }
}
