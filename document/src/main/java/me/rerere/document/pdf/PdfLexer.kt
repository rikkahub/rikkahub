package me.rerere.document.pdf

import java.io.ByteArrayOutputStream

/**
 * PDF 词法/语法解析，既用于文件主体也用于内容流。
 * 解析容错：遇到无法识别的字节会跳过，而不是抛出异常。
 */
internal class PdfLexer(
    val data: ByteArray,
    var pos: Int = 0,
    private val end: Int = data.size,
) {
    // 解析流时用于解析间接引用的 /Length
    var lengthResolver: ((PdfObject) -> Int?)? = null

    fun skipWhitespace() {
        while (pos < end) {
            val c = data[pos].toInt() and 0xFF
            if (isWhitespace(c)) {
                pos++
            } else if (c == '%'.code) {
                while (pos < end && data[pos].toInt() != '\n'.code && data[pos].toInt() != '\r'.code) pos++
            } else {
                return
            }
        }
    }

    /**
     * 读取一个对象。[contentStream] 为 true 时，未知关键字作为 [PdfOperator] 返回。
     * 返回 null 表示读到末尾或遇到结构结束符（`]`、`>>`、endobj 等）。
     */
    fun readObject(contentStream: Boolean = false): PdfObject? {
        skipWhitespace()
        if (pos >= end) return null
        val c = data[pos].toInt() and 0xFF
        return when {
            c == '/'.code -> readName()
            c == '('.code -> readLiteralString()
            c == '<'.code -> {
                if (pos + 1 < end && data[pos + 1].toInt() == '<'.code) {
                    pos += 2
                    readDictBody(contentStream)
                } else {
                    readHexString()
                }
            }

            c == '['.code -> {
                pos++
                readArrayBody(contentStream)
            }

            c == ']'.code || c == '>'.code || c == ')'.code || c == '}'.code || c == '{'.code -> {
                pos++
                if (c == '>'.code && pos < end && data[pos].toInt() == '>'.code) pos++
                if (contentStream) PdfOperator(c.toChar().toString()) else null
            }

            c == '+'.code || c == '-'.code || c == '.'.code || (c in '0'.code..'9'.code) -> {
                val number = readNumber()
                if (contentStream) number else tryReadReference(number)
            }

            else -> readKeyword(contentStream)
        }
    }

    private fun tryReadReference(first: PdfNumber): PdfObject {
        if (first.value < 0 || first.value != Math.floor(first.value)) return first
        val save = pos
        skipWhitespace()
        if (pos < end && isDigit(data[pos].toInt())) {
            val second = readNumber()
            skipWhitespace()
            if (pos < end && data[pos].toInt() == 'R'.code &&
                (pos + 1 >= end || isDelimiterOrWhitespace(data[pos + 1].toInt() and 0xFF))) {
                pos++
                return PdfRef(first.int, second.int)
            }
        }
        pos = save
        return first
    }

    private fun readKeyword(contentStream: Boolean): PdfObject? {
        val start = pos
        while (pos < end && !isDelimiterOrWhitespace(data[pos].toInt() and 0xFF)) pos++
        if (pos == start) {
            // 单个不可识别的分隔符，跳过
            pos++
            return if (contentStream) PdfOperator("") else readObject(false)
        }
        val word = String(data, start, pos - start, Charsets.ISO_8859_1)
        return when (word) {
            "true" -> PdfBoolean(true)
            "false" -> PdfBoolean(false)
            "null" -> PdfNull
            else -> if (contentStream) {
                if (word == "BI") readInlineImage() else PdfOperator(word)
            } else {
                // 文件主体中的 endobj/stream/obj 等关键字由调用方处理
                pos = start
                null
            }
        }
    }

    fun readWord(): String? {
        skipWhitespace()
        val start = pos
        while (pos < end && !isDelimiterOrWhitespace(data[pos].toInt() and 0xFF)) pos++
        return if (pos == start) null else String(data, start, pos - start, Charsets.ISO_8859_1)
    }

    fun peekWord(): String? {
        val save = pos
        val word = readWord()
        pos = save
        return word
    }

    fun readNumber(): PdfNumber {
        val start = pos
        var negative = false
        var seenDigit = false
        var intPart = 0.0
        var frac = 0.0
        var scale = 0.1
        var inFrac = false
        while (pos < end) {
            val c = data[pos].toInt()
            when {
                c == '-'.code -> if (!seenDigit && !inFrac) negative = !negative
                c == '+'.code -> {}
                c == '.'.code -> if (inFrac) break else inFrac = true
                c in '0'.code..'9'.code -> {
                    seenDigit = true
                    if (inFrac) {
                        frac += (c - '0'.code) * scale
                        scale *= 0.1
                    } else {
                        intPart = intPart * 10 + (c - '0'.code)
                    }
                }

                else -> break
            }
            pos++
        }
        if (pos == start) pos++
        val value = intPart + frac
        return PdfNumber(if (negative) -value else value)
    }

    private fun readName(): PdfName {
        pos++ // '/'
        val out = ByteArrayOutputStream()
        while (pos < end) {
            val c = data[pos].toInt() and 0xFF
            if (isDelimiterOrWhitespace(c)) break
            if (c == '#'.code && pos + 2 < end) {
                val h = hexValue(data[pos + 1].toInt())
                val l = hexValue(data[pos + 2].toInt())
                if (h >= 0 && l >= 0) {
                    out.write(h * 16 + l)
                    pos += 3
                    continue
                }
            }
            out.write(c)
            pos++
        }
        val bytes = out.toByteArray()
        // 名字通常是 ASCII；非 ASCII 时合法 UTF-8 按 UTF-8 解释，否则保留原始字节（如 GBK 字体名）
        val name = if (bytes.all { it >= 0 }) {
            String(bytes, Charsets.ISO_8859_1)
        } else {
            try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
        return PdfName(name)
    }

    private fun readLiteralString(): PdfString {
        pos++ // '('
        val out = ByteArrayOutputStream()
        var depth = 1
        while (pos < end) {
            val c = data[pos].toInt() and 0xFF
            pos++
            when (c) {
                '('.code -> {
                    depth++
                    out.write(c)
                }

                ')'.code -> {
                    depth--
                    if (depth == 0) break
                    out.write(c)
                }

                '\\'.code -> {
                    if (pos >= end) break
                    val e = data[pos].toInt() and 0xFF
                    pos++
                    when (e) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '\r'.code -> if (pos < end && data[pos].toInt() == '\n'.code) pos++
                        '\n'.code -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            var n = 1
                            while (n < 3 && pos < end && data[pos].toInt() in '0'.code..'7'.code) {
                                v = v * 8 + (data[pos].toInt() - '0'.code)
                                pos++
                                n++
                            }
                            out.write(v and 0xFF)
                        }

                        else -> out.write(e)
                    }
                }

                '\r'.code -> {
                    // 行尾统一为 \n
                    if (pos < end && data[pos].toInt() == '\n'.code) pos++
                    out.write('\n'.code)
                }

                else -> out.write(c)
            }
        }
        return PdfString(out.toByteArray())
    }

    private fun readHexString(): PdfString {
        pos++ // '<'
        val out = ByteArrayOutputStream()
        var high = -1
        while (pos < end) {
            val c = data[pos].toInt() and 0xFF
            pos++
            if (c == '>'.code) break
            val v = hexValue(c)
            if (v < 0) continue
            if (high < 0) {
                high = v
            } else {
                out.write(high * 16 + v)
                high = -1
            }
        }
        if (high >= 0) out.write(high * 16)
        return PdfString(out.toByteArray())
    }

    private fun readArrayBody(contentStream: Boolean): PdfArray {
        val items = ArrayList<PdfObject>()
        while (true) {
            skipWhitespace()
            if (pos >= end) break
            if (data[pos].toInt() == ']'.code) {
                pos++
                break
            }
            val save = pos
            val obj = readObject(contentStream = false)
            if (obj == null) {
                // 数组中出现关键字（损坏或内容流中的异常数据），跳过一个单词
                if (pos == save) {
                    val word = readWord()
                    if (word == null) pos++
                    if (word == "endobj" || word == "stream") {
                        pos = save
                        break
                    }
                }
                continue
            }
            items.add(obj)
        }
        return PdfArray(items)
    }

    private fun readDictBody(contentStream: Boolean): PdfObject {
        val map = LinkedHashMap<String, PdfObject>()
        while (true) {
            skipWhitespace()
            if (pos >= end) break
            val c = data[pos].toInt()
            if (c == '>'.code) {
                pos++
                if (pos < end && data[pos].toInt() == '>'.code) pos++
                break
            }
            if (c != '/'.code) {
                // 非法键，跳过一个对象
                val save = pos
                readObject(contentStream = false)
                if (pos == save) {
                    val word = readWord()
                    if (word == null) pos++
                    if (word == "endobj" || word == "stream") {
                        pos = save
                        break
                    }
                }
                continue
            }
            val key = readName().name
            skipWhitespace()
            if (pos < end && data[pos].toInt() == '>'.code) {
                // 最后一个键缺少值
                pos++
                if (pos < end && data[pos].toInt() == '>'.code) pos++
                break
            }
            val value = readObject(contentStream = false) ?: run {
                // 值缺失，可能是关键字，跳过
                val word = peekWord()
                if (word == "endobj" || word == "stream") return finishDict(map, contentStream)
                PdfNull
            }
            map[key] = value
        }
        return finishDict(map, contentStream)
    }

    private fun finishDict(map: Map<String, PdfObject>, contentStream: Boolean): PdfObject {
        val dict = PdfDict(map)
        if (contentStream) return dict
        // 检查是否紧跟 stream
        val save = pos
        skipWhitespace()
        if (peekWord() == "stream") {
            readWord()
            return PdfStream(dict, readStreamData(dict))
        }
        pos = save
        return dict
    }

    private fun readStreamData(dict: PdfDict): ByteArray {
        // stream 关键字后应为 CRLF 或 LF，容忍单独的 CR
        if (pos < end && data[pos].toInt() == '\r'.code) pos++
        if (pos < end && data[pos].toInt() == '\n'.code) pos++
        val start = pos
        val length = dict["Length"]?.let { lengthResolver?.invoke(it) ?: (it as? PdfNumber)?.int }
        if (length != null && length >= 0 && start + length <= end) {
            var p = start + length
            while (p < end && isWhitespace(data[p].toInt() and 0xFF)) p++
            if (matches(p, ENDSTREAM)) {
                pos = p + ENDSTREAM.size
                return data.copyOfRange(start, start + length)
            }
        }
        // Length 缺失或错误时搜索 endstream
        val found = indexOf(ENDSTREAM, start)
        val stop = if (found < 0) end else found
        var dataEnd = stop
        if (dataEnd > start && data[dataEnd - 1].toInt() == '\n'.code) dataEnd--
        if (dataEnd > start && data[dataEnd - 1].toInt() == '\r'.code) dataEnd--
        pos = if (found < 0) end else found + ENDSTREAM.size
        return data.copyOfRange(start, dataEnd)
    }

    private fun readInlineImage(): PdfObject {
        // 读取 BI 后的字典直到 ID，然后跳过二进制数据直到 EI
        while (pos < end) {
            skipWhitespace()
            val save = pos
            val word = peekWord()
            if (word == "ID") {
                readWord()
                break
            }
            if (readObject(contentStream = false) == null && pos == save) pos++
        }
        if (pos < end) pos++ // ID 后的单个空白
        var p = pos
        while (p + 1 < end) {
            if (data[p].toInt() == 'E'.code && data[p + 1].toInt() == 'I'.code &&
                (p == 0 || isWhitespace(data[p - 1].toInt() and 0xFF)) &&
                (p + 2 >= end || isDelimiterOrWhitespace(data[p + 2].toInt() and 0xFF))
            ) {
                pos = p + 2
                return PdfInlineImage
            }
            p++
        }
        pos = end
        return PdfInlineImage
    }

    fun matches(at: Int, pattern: ByteArray): Boolean {
        if (at < 0 || at + pattern.size > end) return false
        for (i in pattern.indices) if (data[at + i] != pattern[i]) return false
        return true
    }

    fun indexOf(pattern: ByteArray, from: Int): Int {
        var i = from
        val last = end - pattern.size
        val first = pattern[0]
        while (i <= last) {
            if (data[i] == first && matches(i, pattern)) return i
            i++
        }
        return -1
    }

    companion object {
        private val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)

        fun isWhitespace(c: Int): Boolean =
            c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00

        fun isDelimiterOrWhitespace(c: Int): Boolean = isWhitespace(c) || when (c) {
            '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code, '{'.code, '}'.code, '/'.code, '%'.code -> true
            else -> false
        }

        fun isDigit(c: Int): Boolean = c in '0'.code..'9'.code

        fun hexValue(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
