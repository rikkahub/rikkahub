package me.rerere.document.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 流解码。只实现文字提取需要的通用过滤器；图片专用过滤器（DCT/JPX/JBIG2/CCITT）返回 null。
 */
internal object PdfFilters {
    // 防止解压炸弹
    private const val MAX_DECODED_SIZE = 256 * 1024 * 1024

    fun decode(data: ByteArray, filters: List<String>, params: List<PdfDict?>): ByteArray? {
        var current = data
        for ((index, filter) in filters.withIndex()) {
            val param = params.getOrNull(index)
            current = when (filter) {
                "FlateDecode", "Fl" -> applyPredictor(inflate(current), param)
                "LZWDecode", "LZW" -> applyPredictor(lzw(current, param.int("EarlyChange") ?: 1), param)
                "ASCII85Decode", "A85" -> ascii85(current)
                "ASCIIHexDecode", "AHx" -> asciiHex(current)
                "RunLengthDecode", "RL" -> runLength(current)
                "Crypt" -> current
                else -> return null
            }
        }
        return current
    }

    private fun PdfDict?.int(key: String): Int? = (this?.get(key) as? PdfNumber)?.int

    fun inflate(data: ByteArray): ByteArray {
        val result = inflate(data, nowrap = false)
        // 缺少 zlib 头的流按 raw deflate 再试一次
        if (result.isEmpty() && data.isNotEmpty()) return inflate(data, nowrap = true)
        return result
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        val out = ByteArrayOutputStream(maxOf(64, data.size * 3))
        try {
            inflater.setInput(data)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (_: DataFormatException) {
                    // 损坏的流：保留已解出的部分
                    break
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_DECODED_SIZE) break
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun applyPredictor(data: ByteArray, params: PdfDict?): ByteArray {
        val predictor = params.int("Predictor") ?: 1
        if (predictor <= 1) return data
        val colors = params.int("Colors") ?: 1
        val bpc = params.int("BitsPerComponent") ?: 8
        val columns = params.int("Columns") ?: 1
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val rowLength = (columns * colors * bpc + 7) / 8
        if (rowLength <= 0) return data
        if (predictor == 2) {
            if (bpc != 8) return data
            val out = data.copyOf()
            var row = 0
            while (row + rowLength <= out.size) {
                for (i in bpp until rowLength) {
                    out[row + i] = (out[row + i] + out[row + i - bpp]).toByte()
                }
                row += rowLength
            }
            return out
        }
        // PNG 预测器：每行前有一个类型字节
        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLength)
        var pos = 0
        while (pos < data.size) {
            val type = data[pos].toInt() and 0xFF
            pos++
            val cur = ByteArray(rowLength)
            val n = minOf(rowLength, data.size - pos)
            System.arraycopy(data, pos, cur, 0, n)
            pos += n
            for (i in 0 until rowLength) {
                val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val raw = cur[i].toInt() and 0xFF
                cur[i] = when (type) {
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upLeft)
                    else -> raw
                }.toByte()
            }
            out.write(cur, 0, n)
            prev = cur
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    private fun lzw(data: ByteArray, earlyChange: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size * 3)
        val table = ArrayList<ByteArray>(4096)
        fun reset() {
            table.clear()
            for (i in 0 until 256) table.add(byteArrayOf(i.toByte()))
            table.add(ByteArray(0)) // 256 clear
            table.add(ByteArray(0)) // 257 eod
        }
        reset()
        var width = 9
        var bitBuf = 0L
        var bitCount = 0
        var pos = 0
        var prev: ByteArray? = null
        while (true) {
            while (bitCount < width && pos < data.size) {
                bitBuf = (bitBuf shl 8) or (data[pos].toLong() and 0xFF)
                bitCount += 8
                pos++
            }
            if (bitCount < width) break
            val code = ((bitBuf shr (bitCount - width)) and ((1L shl width) - 1)).toInt()
            bitCount -= width
            if (code == 256) {
                reset()
                width = 9
                prev = null
                continue
            }
            if (code == 257) break
            val entry = when {
                code < table.size -> table[code]
                code == table.size && prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (prev != null && table.size < 4096) table.add(prev + entry[0])
            prev = entry
            val next = table.size + earlyChange
            width = when {
                next >= 2048 -> 12
                next >= 1024 -> 11
                next >= 512 -> 10
                else -> 9
            }
            if (out.size() > MAX_DECODED_SIZE) break
        }
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        val group = IntArray(5)
        var n = 0
        var i = 0
        // 跳过可选的 <~ 前缀
        while (i < data.size && PdfLexer.isWhitespace(data[i].toInt() and 0xFF)) i++
        if (i + 1 < data.size && data[i].toInt() == '<'.code && data[i + 1].toInt() == '~'.code) i += 2
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            if (c == '~'.code) break
            if (PdfLexer.isWhitespace(c)) continue
            if (c == 'z'.code && n == 0) {
                repeat(4) { out.write(0) }
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            group[n++] = c - '!'.code
            if (n == 5) {
                var v = 0L
                for (g in group) v = v * 85 + g
                for (s in 3 downTo 0) out.write(((v shr (s * 8)) and 0xFF).toInt())
                n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) group[k] = 84
            var v = 0L
            for (g in group) v = v * 85 + g
            for (s in 0 until n - 1) out.write(((v shr ((3 - s) * 8)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2)
        var high = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = PdfLexer.hexValue(c)
            if (v < 0) continue
            if (high < 0) {
                high = v
            } else {
                out.write(high * 16 + v)
                high = -1
            }
        }
        if (high >= 0) out.write(high * 16)
        return out.toByteArray()
    }

    private fun runLength(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            i++
            when {
                len == 128 -> break
                len < 128 -> {
                    val n = minOf(len + 1, data.size - i)
                    out.write(data, i, n)
                    i += n
                }

                else -> {
                    if (i >= data.size) break
                    val b = data[i].toInt()
                    i++
                    repeat(257 - len) { out.write(b) }
                }
            }
        }
        return out.toByteArray()
    }
}
