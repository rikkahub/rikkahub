package me.rerere.document.pdf

/**
 * PDF 文件结构：交叉引用表、对象解析、解密和页面树。
 * xref 损坏或缺失时扫描全文重建。
 */
internal class PdfFile(private val data: ByteArray, password: String = "") {
    private sealed interface XrefEntry
    private data class InFile(val offset: Int, val gen: Int) : XrefEntry
    private data class InObjectStream(val streamNum: Int, val index: Int) : XrefEntry

    private val xref = HashMap<Int, XrefEntry>()
    private val cache = HashMap<Int, PdfObject>()
    private val objectStreams = HashMap<Int, Map<Int, PdfObject>>()
    private val resolving = HashSet<Int>()
    private var repaired = false
    private var encryptRef: Int = -1
    private var security: PdfSecurity? = null

    var trailer: PdfDict = PdfDict(emptyMap())
        private set

    init {
        if (data.size < 8) throw PdfException("File is too small to be a PDF")
        val loaded = runCatching { loadXrefChain() }.getOrDefault(false)
        // 找不到 Catalog 时不报错，pages() 会退回到扫描 /Type /Page 对象
        if (!loaded || catalog() == null) repair()

        val encrypt = trailer["Encrypt"]
        if (encrypt != null) {
            if (encrypt is PdfRef) encryptRef = encrypt.num
            val dict = resolve(encrypt) as? PdfDict
            if (dict != null) {
                val id = ((trailer["ID"] as? PdfArray)?.get(0) as? PdfString)?.bytes ?: ByteArray(0)
                security = PdfSecurity(dict, id, password)
                // 加密信息就绪前读到的对象可能未解密
                cache.clear()
                objectStreams.clear()
            }
        }
    }

    fun catalog(): PdfDict? = runCatching { resolve(trailer["Root"]) as? PdfDict }.getOrNull()

    // region xref

    private fun loadXrefChain(): Boolean {
        val startxref = lastIndexOf(STARTXREF, data.size - 1)
        if (startxref < 0) return false
        val lexer = PdfLexer(data, startxref + STARTXREF.size)
        lexer.skipWhitespace()
        var offset = lexer.readNumber().int
        val visited = HashSet<Int>()
        var first = true
        while (offset > 0 && offset < data.size && visited.add(offset)) {
            val section = readXrefSection(offset) ?: return !first && trailer["Root"] != null
            if (first) {
                trailer = section
                first = false
            } else {
                // 旧版本 trailer 中缺失的键由新版本补充
                trailer = PdfDict(section.map + trailer.map)
            }
            offset = (section["Prev"] as? PdfNumber)?.int ?: break
        }
        return !first
    }

    // 读取一个 xref 节，返回其 trailer；条目只在不存在时写入（新节优先）
    private fun readXrefSection(offset: Int): PdfDict? {
        val lexer = PdfLexer(data, offset)
        lexer.skipWhitespace()
        if (lexer.matches(lexer.pos, XREF_KEYWORD)) {
            lexer.readWord()
            return readXrefTable(lexer)
        }
        // xref 流
        val obj = readIndirectObjectAt(offset, expectedNum = null)?.second as? PdfStream ?: return null
        if ((obj.dict["Type"] as? PdfName)?.name != "XRef") return null
        readXrefStream(obj)
        return obj.dict
    }

    private fun readXrefTable(lexer: PdfLexer): PdfDict? {
        val entries = ArrayList<Pair<Int, InFile>>()
        while (true) {
            lexer.skipWhitespace()
            val word = lexer.peekWord() ?: return null
            if (word == "trailer") {
                lexer.readWord()
                val trailer = lexer.readObject() as? PdfDict
                // 混合引用文件：XRefStm 中的条目优先于同一节的表（表中常把这些对象标为已释放）
                (trailer?.get("XRefStm") as? PdfNumber)?.int?.let { readXrefSection(it) }
                for ((num, entry) in entries) {
                    if (num !in xref) xref[num] = entry
                }
                return trailer
            }
            if (!word[0].isDigit()) return null
            val start = lexer.readNumber().int
            lexer.skipWhitespace()
            val count = lexer.readNumber().int
            for (i in 0 until count) {
                lexer.skipWhitespace()
                val off = lexer.readNumber().value.toLong()
                lexer.skipWhitespace()
                val gen = lexer.readNumber().int
                val type = lexer.readWord()
                val num = start + i
                if (type == "n" && off > 0) {
                    entries.add(num to InFile(off.toInt(), gen))
                } else if (type == "f") {
                    // 占位，阻止更旧的节覆盖已释放的对象
                    entries.add(num to InFile(-1, gen))
                }
            }
        }
    }

    private fun readXrefStream(stream: PdfStream) {
        val dict = stream.dict
        val w = (dict["W"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.int ?: 0 } ?: return
        if (w.size < 3) return
        val decoded = decodeStream(stream) ?: return
        val size = (dict["Size"] as? PdfNumber)?.int ?: 0
        val index = (dict["Index"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.int ?: 0 } ?: listOf(0, size)
        val rowSize = w.sum()
        var pos = 0
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i]
            val count = index[i + 1]
            for (k in 0 until count) {
                if (pos + rowSize > decoded.size) return
                val type = if (w[0] == 0) 1 else readInt(decoded, pos, w[0])
                val f2 = readInt(decoded, pos + w[0], w[1])
                val f3 = readInt(decoded, pos + w[0] + w[1], w[2])
                pos += rowSize
                val num = start + k
                if (num in xref) continue
                when (type) {
                    0 -> xref[num] = InFile(-1, f3)
                    1 -> xref[num] = InFile(f2, f3)
                    2 -> xref[num] = InObjectStream(f2, f3)
                }
            }
            i += 2
        }
    }

    private fun readInt(buf: ByteArray, pos: Int, width: Int): Int {
        var v = 0
        for (i in 0 until width) v = (v shl 8) or (buf[pos + i].toInt() and 0xFF)
        return v
    }

    /**
     * 扫描全文重建 xref：记录每个 "n g obj" 的最后一次出现，并从 trailer 或 Catalog 对象恢复根。
     */
    private fun repair() {
        if (repaired) return
        repaired = true
        xref.clear()
        cache.clear()
        objectStreams.clear()
        var newTrailer: PdfDict? = null
        var i = 0
        val n = data.size
        while (i < n) {
            val c = data[i].toInt()
            if (c == 'o'.code && i + 3 <= n && data[i + 1].toInt() == 'b'.code && data[i + 2].toInt() == 'j'.code &&
                (i + 3 == n || PdfLexer.isDelimiterOrWhitespace(data[i + 3].toInt() and 0xFF))
            ) {
                parseObjHeaderBackwards(i)?.let { (num, gen, start) -> xref[num] = InFile(start, gen) }
            } else if (c == 't'.code && matchesAt(i, TRAILER_KEYWORD)) {
                val lexer = PdfLexer(data, i + TRAILER_KEYWORD.size)
                val dict = runCatching { lexer.readObject() as? PdfDict }.getOrNull()
                if (dict?.get("Root") != null) newTrailer = dict
            }
            i++
        }
        // 对象流中的对象（直接出现在文件中的对象优先）
        val directNums = xref.keys.toList()
        for (num in directNums) {
            val obj = runCatching { resolve(PdfRef(num, 0)) }.getOrNull() as? PdfStream ?: continue
            when ((obj.dict["Type"] as? PdfName)?.name) {
                "ObjStm" -> {
                    val nums = objectStreamNumbers(obj)
                    nums.forEachIndexed { index, objNum -> if (objNum !in xref) xref[objNum] = InObjectStream(num, index) }
                }

                "XRef" -> if (newTrailer == null && obj.dict["Root"] != null) newTrailer = obj.dict
            }
        }
        cache.clear()
        if (newTrailer == null) {
            // 没有 trailer：寻找 Catalog 对象
            val root = xref.keys.sorted().firstOrNull { num ->
                ((runCatching { resolve(PdfRef(num, 0)) }.getOrNull() as? PdfDict)?.get("Type") as? PdfName)?.name == "Catalog"
            }
            newTrailer = PdfDict(if (root != null) mapOf("Root" to PdfRef(root, 0)) else emptyMap())
        }
        trailer = if (trailer.map.isNotEmpty()) PdfDict(trailer.map + newTrailer.map) else newTrailer
        cache.clear()
    }

    private fun parseObjHeaderBackwards(objPos: Int): Triple<Int, Int, Int>? {
        var p = objPos - 1
        fun skipWs() {
            while (p >= 0 && PdfLexer.isWhitespace(data[p].toInt() and 0xFF)) p--
        }

        fun readIntBackwards(): Int? {
            val end = p
            while (p >= 0 && PdfLexer.isDigit(data[p].toInt())) p--
            if (p == end || end - p > 10) return null
            return String(data, p + 1, end - p, Charsets.ISO_8859_1).toLongOrNull()?.toInt()
        }
        skipWs()
        val gen = readIntBackwards() ?: return null
        skipWs()
        val num = readIntBackwards() ?: return null
        if (p >= 0 && !PdfLexer.isDelimiterOrWhitespace(data[p].toInt() and 0xFF)) return null
        return Triple(num, gen, p + 1)
    }

    // endregion

    // region objects

    fun resolve(obj: PdfObject?): PdfObject? {
        var current = obj
        var depth = 0
        while (current is PdfRef) {
            if (depth++ > 32) return null
            current = getObject(current.num)
        }
        return current
    }

    private fun getObject(num: Int): PdfObject? {
        cache[num]?.let { return it }
        if (!resolving.add(num)) return null
        try {
            val obj = loadObject(num) ?: if (!repaired) {
                // xref 指向错误：重建后再试一次
                repair()
                loadObject(num)
            } else {
                null
            }
            val result = obj ?: PdfNull
            cache[num] = result
            return result
        } finally {
            resolving.remove(num)
        }
    }

    private fun loadObject(num: Int): PdfObject? {
        return when (val entry = xref[num] ?: return PdfNull) {
            is InFile -> {
                if (entry.offset < 0) return PdfNull
                val (gen, obj) = readIndirectObjectAt(entry.offset, num) ?: return null
                decryptObject(obj, num, gen)
            }

            is InObjectStream -> {
                val objects = objectStreams.getOrPut(entry.streamNum) { loadObjectStream(entry.streamNum) }
                objects[num]
            }
        }
    }

    private fun readIndirectObjectAt(offset: Int, expectedNum: Int?): Pair<Int, PdfObject>? {
        if (offset < 0 || offset >= data.size) return null
        val lexer = PdfLexer(data, offset)
        lexer.skipWhitespace()
        val num = lexer.readWord()?.toIntOrNull() ?: return null
        if (expectedNum != null && num != expectedNum) return null
        val gen = lexer.readWord()?.toIntOrNull() ?: return null
        val keyword = lexer.readWord()
        if (keyword != "obj") {
            // "1 0 obj<<" 这样关键字与对象相连的情况
            if (keyword?.startsWith("obj") != true) return null
            lexer.pos -= keyword.length - 3
        }
        lexer.lengthResolver = { length ->
            if (length is PdfRef) (resolve(length) as? PdfNumber)?.int else (length as? PdfNumber)?.int
        }
        val obj = lexer.readObject() ?: PdfNull
        return gen to obj
    }

    private fun loadObjectStream(streamNum: Int): Map<Int, PdfObject> {
        val stream = resolve(PdfRef(streamNum, 0)) as? PdfStream ?: return emptyMap()
        val decoded = decodeStream(stream) ?: return emptyMap()
        val n = (stream.dict["N"] as? PdfNumber)?.int ?: return emptyMap()
        val first = (stream.dict["First"] as? PdfNumber)?.int ?: return emptyMap()
        val lexer = PdfLexer(decoded)
        val headers = ArrayList<Pair<Int, Int>>(n)
        repeat(n) {
            lexer.skipWhitespace()
            val num = lexer.readNumber().int
            lexer.skipWhitespace()
            val off = lexer.readNumber().int
            headers.add(num to off)
        }
        val result = HashMap<Int, PdfObject>(n * 2)
        for ((num, off) in headers) {
            if (first + off >= decoded.size) continue
            lexer.pos = first + off
            val obj = runCatching { lexer.readObject() }.getOrNull() ?: continue
            result[num] = obj
        }
        return result
    }

    private fun objectStreamNumbers(stream: PdfStream): List<Int> {
        val decoded = decodeStream(stream) ?: return emptyList()
        val n = (stream.dict["N"] as? PdfNumber)?.int ?: return emptyList()
        val lexer = PdfLexer(decoded)
        return List(n) {
            lexer.skipWhitespace()
            val num = lexer.readNumber().int
            lexer.skipWhitespace()
            lexer.readNumber()
            num
        }
    }

    private fun decryptObject(obj: PdfObject, num: Int, gen: Int): PdfObject {
        val sec = security ?: return obj
        if (num == encryptRef) return obj
        return decryptValue(obj, sec, num, gen)
    }

    private fun decryptValue(obj: PdfObject, sec: PdfSecurity, num: Int, gen: Int): PdfObject = when (obj) {
        is PdfString -> PdfString(sec.decryptString(obj.bytes, num, gen))
        is PdfArray -> PdfArray(obj.items.map { decryptValue(it, sec, num, gen) })
        is PdfDict -> PdfDict(obj.map.mapValues { decryptValue(it.value, sec, num, gen) })
        is PdfStream -> {
            val dict = PdfDict(obj.dict.map.mapValues { decryptValue(it.value, sec, num, gen) })
            val type = (dict["Type"] as? PdfName)?.name
            val identityCrypt = streamFilters(dict).firstOrNull() == "Crypt"
            if (type == "XRef" || identityCrypt) PdfStream(dict, obj.raw) else PdfStream(dict, sec.decryptStream(obj.raw, num, gen))
        }

        else -> obj
    }

    /** 解码流数据（流对象在加载时已解密） */
    fun decodeStream(stream: PdfStream): ByteArray? {
        val raw = stream.raw
        val filters = streamFilters(stream.dict)
        if (filters.isEmpty()) return raw
        val params = when (val p = resolve(stream.dict["DecodeParms"] ?: stream.dict["DP"])) {
            is PdfDict -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDict }
            else -> emptyList()
        }
        return PdfFilters.decode(raw, filters, params)
    }

    private fun streamFilters(dict: PdfDict): List<String> = when (val f = resolve(dict["Filter"] ?: dict["F"])) {
        is PdfName -> listOf(f.name)
        is PdfArray -> f.items.mapNotNull { (resolve(it) as? PdfName)?.name }
        else -> emptyList()
    }

    // endregion

    // region pages

    fun pages(): List<PdfDict> {
        val root = resolve(catalog()?.get("Pages")) as? PdfDict
        val result = ArrayList<PdfDict>()
        if (root != null) collectPages(root, result, HashSet(), null, 0)
        if (result.isEmpty() && !repaired) {
            // 页面树损坏：从全部对象中找 /Type /Page
            repair()
            val fallbackRoot = resolve(catalog()?.get("Pages")) as? PdfDict
            if (fallbackRoot != null) collectPages(fallbackRoot, result, HashSet(), null, 0)
        }
        if (result.isEmpty()) {
            for (num in xref.keys.sorted()) {
                val dict = runCatching { resolve(PdfRef(num, 0)) }.getOrNull() as? PdfDict ?: continue
                if ((dict["Type"] as? PdfName)?.name == "Page") result.add(dict)
            }
        }
        return result
    }

    private fun collectPages(node: PdfDict, out: MutableList<PdfDict>, visited: HashSet<PdfDict>, inherited: PdfDict?, depth: Int) {
        if (depth > 64 || !visited.add(node)) return
        // Resources 等属性可从父节点继承
        val merged = if (inherited == null) {
            node
        } else {
            val map = LinkedHashMap(node.map)
            for (key in INHERITABLE) if (key !in map && inherited[key] != null) map[key] = inherited[key]!!
            PdfDict(map)
        }
        val kids = resolve(node["Kids"]) as? PdfArray
        val type = (node["Type"] as? PdfName)?.name
        if (kids == null || type == "Page") {
            if (type != "Pages") out.add(merged)
            return
        }
        for (kid in kids.items) {
            val child = resolve(kid) as? PdfDict ?: continue
            collectPages(child, out, visited, merged, depth + 1)
        }
    }

    // endregion

    private fun matchesAt(at: Int, pattern: ByteArray): Boolean {
        if (at + pattern.size > data.size) return false
        for (k in pattern.indices) if (data[at + k] != pattern[k]) return false
        return true
    }

    private fun lastIndexOf(pattern: ByteArray, from: Int): Int {
        var i = minOf(from, data.size - pattern.size)
        while (i >= 0) {
            if (data[i] == pattern[0] && matchesAt(i, pattern)) return i
            i--
        }
        return -1
    }

    companion object {
        private val STARTXREF = "startxref".toByteArray(Charsets.ISO_8859_1)
        private val XREF_KEYWORD = "xref".toByteArray(Charsets.ISO_8859_1)
        private val TRAILER_KEYWORD = "trailer".toByteArray(Charsets.ISO_8859_1)
        private val INHERITABLE = listOf("Resources", "MediaBox", "CropBox", "Rotate")
    }
}
