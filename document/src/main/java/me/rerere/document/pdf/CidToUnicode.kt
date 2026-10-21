package me.rerere.document.pdf

/**
 * Adobe 中日韩字符集（Adobe-GB1/CNS1/Japan1/Korea1）的 CID → Unicode 表。
 * 用于没有 ToUnicode 的 CID 字体（如 xdvipdfmx 输出的 Identity-H 字体）。
 *
 * 资源文件由 Adobe cmap-resources 的 cid2code.txt 生成，deflate 压缩，内容为若干段
 * [varint CID 间隔][varint 段长][zigzag varint Unicode 差值]，段内 CID 与 Unicode 同步递增。
 */
internal object CidToUnicode {
    private val tables = HashMap<String, IntArray?>()
    private val reverseTables = HashMap<String, Map<Int, Int>>()

    @Synchronized
    fun lookup(ordering: String, cid: Int): String? {
        val table = tables.getOrPut(ordering) { load(ordering) } ?: return null
        val cp = table.getOrNull(cid) ?: return null
        return if (cp > 0) String(Character.toChars(cp)) else null
    }

    /** Unicode → CID 反查（同一码位有多个 CID 时取最小的，即比例宽度的字形） */
    @Synchronized
    fun cidOf(ordering: String, codePoint: Int): Int? {
        val reverse = reverseTables.getOrPut(ordering) {
            val table = tables.getOrPut(ordering) { load(ordering) } ?: return null
            HashMap<Int, Int>(table.size).also { map ->
                for (cid in table.indices) if (table[cid] > 0) map.putIfAbsent(table[cid], cid)
            }
        }
        return reverse[codePoint]
    }

    fun supports(ordering: String): Boolean = ordering in ORDERINGS

    private fun load(ordering: String): IntArray? {
        if (ordering !in ORDERINGS) return null
        val compressed =
            CidToUnicode::class.java.getResourceAsStream("$RESOURCE_DIR/cid-$ordering.bin")?.use { it.readBytes() }
                ?: return null
        val data = PdfFilters.inflate(compressed)
        val cids = ArrayList<Int>()
        val unicodes = ArrayList<Int>()
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        var nextCid = 0
        var prevUnicode = 0L
        var maxCid = 0
        while (pos < data.size) {
            val cid = nextCid + varint().toInt()
            val length = varint().toInt()
            val zigzag = varint()
            val unicode = prevUnicode + ((zigzag ushr 1) xor -(zigzag and 1))
            cids.add(cid)
            cids.add(length)
            unicodes.add(unicode.toInt())
            nextCid = cid + length
            prevUnicode = unicode
            maxCid = nextCid
        }
        val table = IntArray(maxCid)
        for (i in unicodes.indices) {
            val start = cids[i * 2]
            val length = cids[i * 2 + 1]
            for (k in 0 until length) table[start + k] = unicodes[i] + k
        }
        return table
    }

    private val ORDERINGS = setOf("GB1", "CNS1", "Japan1", "Korea1")
}
