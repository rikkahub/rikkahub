package me.rerere.document.pdf

import java.text.Bidi
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 解释页面内容流，按字形坐标还原行、空格和段落。
 * 文字按内容流顺序输出（大多数生成器的绘制顺序即阅读顺序），不做版面重排。
 */
internal class PdfTextExtractor(private val file: PdfFile) {
    private val fontCache = HashMap<Any, PdfFont>()

    // 默认配置下关闭的可选内容组（按解析后的对象实例比较）
    private val hiddenGroups: Set<PdfObject> by lazy { loadHiddenGroups() }

    private fun loadHiddenGroups(): Set<PdfObject> {
        val properties = file.resolve(file.catalog()?.get("OCProperties")) as? PdfDict ?: return emptySet()
        val config = file.resolve(properties["D"]) as? PdfDict ?: return emptySet()
        fun groups(key: String, from: PdfDict): List<PdfObject> =
            (file.resolve(from[key]) as? PdfArray)?.items?.mapNotNull { file.resolve(it) }.orEmpty()
        val on = groups("ON", config).toSet()
        val baseOff = (config["BaseState"] as? PdfName)?.name == "OFF"
        val hidden = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PdfObject, Boolean>())
        if (baseOff) groups("OCGs", properties).filter { it !in on }.forEach { hidden.add(it) }
        groups("OFF", config).forEach { hidden.add(it) }
        return hidden
    }

    /** 可选内容（OCG 或 OCMD）在默认配置下是否隐藏 */
    private fun isHidden(oc: PdfObject?): Boolean {
        val dict = file.resolve(oc) as? PdfDict ?: return false
        if ((dict["Type"] as? PdfName)?.name != "OCMD") return dict in hiddenGroups
        (file.resolve(dict["VE"]) as? PdfArray)?.let { return !evaluate(it, 0) }
        val groups = when (val g = file.resolve(dict["OCGs"])) {
            is PdfArray -> g.items.mapNotNull { file.resolve(it) }
            null -> return false
            else -> listOf(g)
        }
        val visible = groups.map { it !in hiddenGroups }
        return !when ((dict["P"] as? PdfName)?.name) {
            "AllOn" -> visible.all { it }
            "AnyOff" -> visible.any { !it }
            "AllOff" -> visible.none { it }
            else -> visible.any { it }
        }
    }

    // 可见性表达式：[/And|/Or|/Not 操作数...]，操作数为 OCG 或嵌套表达式
    private fun evaluate(expr: PdfArray, depth: Int): Boolean {
        if (depth > 16) return true
        val op = (expr[0] as? PdfName)?.name
        val operands = expr.items.drop(1).map { item ->
            when (val v = file.resolve(item)) {
                is PdfArray -> evaluate(v, depth + 1)
                null -> true
                else -> v !in hiddenGroups
            }
        }
        return when (op) {
            "And" -> operands.all { it }
            "Or" -> operands.any { it }
            "Not" -> !(operands.firstOrNull() ?: true)
            else -> true
        }
    }

    fun extract(page: PdfDict): String {
        val builder = LineBuilder()
        interpret(page, builder)
        return builder.finish()
    }

    fun interpret(page: PdfDict, sink: GlyphSink) {
        val resources = file.resolve(page["Resources"]) as? PdfDict
        val pageSink = clipToPage(page, sink)
        Interpreter(pageSink).run(pageContent(page), resources, Matrix.IDENTITY, depth = 0)
        // 注释（表单字段值、FreeText 等）的外观流，与阅读器一样画在页面内容之上；
        // 每个注释从初始图形状态开始，不受页面内容遗留的裁剪等状态影响
        (file.resolve(page["Annots"]) as? PdfArray)?.items?.forEach { item ->
            val annot = file.resolve(item) as? PdfDict ?: return@forEach
            runCatching { drawAnnotation(annot, Interpreter(pageSink)) }
        }
    }

    private fun drawAnnotation(annot: PdfDict, interpreter: Interpreter) {
        val subtype = (annot["Subtype"] as? PdfName)?.name
        if (subtype == "Popup" || subtype == "Link") return
        if (isHidden(annot["OC"])) return
        val flags = (file.resolve(annot["F"]) as? PdfNumber)?.int ?: 0
        if (flags and (2 or 32) != 0) return // Hidden / NoView
        val normal = file.resolve((file.resolve(annot["AP"]) as? PdfDict)?.get("N"))
        val appearance = when (normal) {
            is PdfStream -> normal
            // 复选框等多状态外观：按 /AS 选择
            is PdfDict -> (file.resolve(annot["AS"]) as? PdfName)?.name?.let { file.resolve(normal[it]) as? PdfStream }
            else -> null
        }
        val rect = box(annot["Rect"]) ?: return
        if (appearance == null) {
            fallbackAnnotationText(annot)?.let { interpreter.emitText(it, rect[0], rect[3], 10f) }
            return
        }
        val bbox = box(appearance.dict["BBox"]) ?: return
        val formMatrix = (file.resolve(appearance.dict["Matrix"]) as? PdfArray)?.let { matrixOf(it.items) } ?: Matrix.IDENTITY
        // PDF 12.5.5：把经 Matrix 变换后的 BBox 映射到 Rect
        val corners = listOf(
            formMatrix.apply(bbox[0], bbox[1]), formMatrix.apply(bbox[2], bbox[1]),
            formMatrix.apply(bbox[0], bbox[3]), formMatrix.apply(bbox[2], bbox[3]),
        )
        val tx0 = corners.minOf { it.first }
        val ty0 = corners.minOf { it.second }
        val tx1 = corners.maxOf { it.first }
        val ty1 = corners.maxOf { it.second }
        if (tx1 - tx0 <= 0f || ty1 - ty0 <= 0f) return
        val sx = (rect[2] - rect[0]) / (tx1 - tx0)
        val sy = (rect[3] - rect[1]) / (ty1 - ty0)
        val a = Matrix(sx, 0f, 0f, sy, rect[0] - tx0 * sx, rect[1] - ty0 * sy)
        interpreter.runForm(appearance, null, a, depth = 1)
    }

    // 没有外观流时按阅读器的做法显示：文本框/列表的值、按钮标题、FreeText 的内容
    private fun fallbackAnnotationText(annot: PdfDict): String? {
        fun inherited(key: String): PdfObject? {
            var node: PdfDict? = annot
            repeat(16) {
                val value = node?.get(key)
                if (value != null) return file.resolve(value)
                node = file.resolve(node?.get("Parent")) as? PdfDict ?: return null
            }
            return null
        }
        return when ((annot["Subtype"] as? PdfName)?.name) {
            "FreeText" -> (file.resolve(annot["Contents"]) as? PdfString)?.text()
            "Widget" -> when ((inherited("FT") as? PdfName)?.name) {
                "Tx" -> (inherited("V") as? PdfString)?.text()
                "Ch" -> when (val v = inherited("V")) {
                    is PdfString -> v.text()
                    is PdfArray -> v.items.mapNotNull { (file.resolve(it) as? PdfString)?.text() }.joinToString(", ")
                    else -> null
                }

                "Btn" -> {
                    val flags = (inherited("Ff") as? PdfNumber)?.int ?: 0
                    // 只有按钮（pushbutton）有标题；复选框和单选框没有文字
                    if (flags and (1 shl 16) != 0) {
                        ((file.resolve(annot["MK"]) as? PdfDict)?.get("CA")?.let { file.resolve(it) } as? PdfString)?.text()
                    } else {
                        null
                    }
                }

                else -> null
            }

            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun matrixOf(items: List<PdfObject>): Matrix {
        fun v(i: Int) = (file.resolve(items.getOrNull(i)) as? PdfNumber)?.float ?: 0f
        return Matrix(v(0), v(1), v(2), v(3), v(4), v(5))
    }

    // 丢掉完全落在页面可见区域（CropBox 与 MediaBox 的交集）之外的字形，与阅读器显示一致
    private fun clipToPage(page: PdfDict, sink: GlyphSink): GlyphSink {
        val media = box(page["MediaBox"]) ?: return sink
        val crop = box(page["CropBox"])
        val x0 = maxOf(media[0], crop?.get(0) ?: media[0])
        val y0 = maxOf(media[1], crop?.get(1) ?: media[1])
        val x1 = minOf(media[2], crop?.get(2) ?: media[2])
        val y1 = minOf(media[3], crop?.get(3) ?: media[3])
        if (x1 <= x0 || y1 <= y0) return sink
        return GlyphSink { text, ox, oy, ex, ey, size, dx, dy ->
            // 字形框：基线两端点向上扩展一个字号
            val ux = -dy * size
            val uy = dx * size
            val minX = minOf(ox, ex, ox + ux, ex + ux)
            val maxX = maxOf(ox, ex, ox + ux, ex + ux)
            val minY = minOf(oy, ey, oy + uy, ey + uy)
            val maxY = maxOf(oy, ey, oy + uy, ey + uy)
            if (maxX >= x0 && minX <= x1 && maxY >= y0 && minY <= y1) {
                sink.add(text, ox, oy, ex, ey, size, dx, dy)
            }
        }
    }

    private fun box(obj: PdfObject?): FloatArray? {
        val items = (file.resolve(obj) as? PdfArray)?.items ?: return null
        if (items.size < 4) return null
        val v = FloatArray(4) { (file.resolve(items[it]) as? PdfNumber)?.float ?: return null }
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    private fun pageContent(page: PdfDict): ByteArray {
        val streams = when (val contents = file.resolve(page["Contents"])) {
            is PdfStream -> listOf(contents)
            is PdfArray -> contents.items.mapNotNull { file.resolve(it) as? PdfStream }
            else -> emptyList()
        }
        if (streams.size == 1) return file.decodeStream(streams[0]) ?: ByteArray(0)
        // 多个内容流之间可能在任意 token 处切分，拼接时用换行分隔
        val out = java.io.ByteArrayOutputStream()
        for (s in streams) {
            file.decodeStream(s)?.let { out.write(it) }
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun font(ref: PdfObject?): PdfFont? {
        val key: Any = (ref as? PdfRef) ?: (file.resolve(ref) ?: return null)
        fontCache[key]?.let { return it }
        val dict = file.resolve(ref) as? PdfDict ?: return null
        return PdfFont.load(file, dict).also { fontCache[key] = it }
    }

    private class TextState(
        var font: PdfFont? = null,
        var fontSize: Float = 0f,
        var charSpacing: Float = 0f,
        var wordSpacing: Float = 0f,
        var horizontalScale: Float = 1f,
        var leading: Float = 0f,
        var rise: Float = 0f,
        // 当前裁剪区域（设备空间的轴对齐矩形），随 q/Q 保存恢复；null 表示不裁剪
        var clip: Rect? = null,
    ) {
        fun copy() = TextState(font, fontSize, charSpacing, wordSpacing, horizontalScale, leading, rise, clip)
    }

    private inner class Interpreter(private val sink: GlyphSink) {
        private var ctm = Matrix.IDENTITY
        private var state = TextState()
        private val stack = ArrayList<Pair<Matrix, TextState>>()
        private var tm = Matrix.IDENTITY
        private var tlm = Matrix.IDENTITY
        private val formStack = HashSet<PdfStream>()

        // 当前路径：只跟踪由 re 组成的矩形路径，用于 `re W n` 这类矩形裁剪
        private var pathBounds: Rect? = null
        private var pathIsRects = true
        private var pendingClip = false

        // 每页 Form XObject 的执行次数，防止共享子表单层层扇出导致指数级展开
        private var formRuns = 0

        // 标记内容栈：每层记录是否开启了 ActualText（MC_ACTUAL_TEXT）或隐藏的可选内容（MC_HIDDEN）
        private val markedContent = ArrayList<Int>()
        private var hiddenDepth = 0
        private var actualText: String? = null
        private var actualTextEmitted = false

        // ActualText 标记内没有字形时（如给图片加的替代文字），在标记内最后绘制的图片处输出
        private var actualTextAnchor: Pair<Float, Float>? = null

        fun run(content: ByteArray, resources: PdfDict?, initial: Matrix, depth: Int) {
            ctm = initial
            execute(content, resources, depth)
        }

        private fun execute(content: ByteArray, resources: PdfDict?, depth: Int) {
            val lexer = PdfLexer(content)
            val operands = ArrayList<PdfObject>(8)
            val fonts = file.resolve(resources?.get("Font")) as? PdfDict
            val xObjects = file.resolve(resources?.get("XObject")) as? PdfDict
            val properties = file.resolve(resources?.get("Properties")) as? PdfDict
            while (true) {
                val obj = try {
                    lexer.readObject(contentStream = true)
                } catch (_: Exception) {
                    null
                } ?: break
                if (obj === PdfInlineImage) {
                    if (actualText != null) actualTextAnchor = ctm.apply(0f, 1f)
                    continue
                }
                if (obj !is PdfOperator) {
                    if (operands.size < 64) operands.add(obj)
                    continue
                }
                try {
                    operator(obj.name, operands, fonts, xObjects, properties, resources, depth)
                } catch (_: Exception) {
                    // 单个操作符出错不影响后续内容
                }
                operands.clear()
            }
        }

        private fun num(operands: List<PdfObject>, i: Int): Float = (operands.getOrNull(i) as? PdfNumber)?.float ?: 0f

        private fun operator(
            op: String,
            operands: List<PdfObject>,
            fonts: PdfDict?,
            xObjects: PdfDict?,
            properties: PdfDict?,
            resources: PdfDict?,
            depth: Int,
        ) {
            when (op) {
                "q" -> if (stack.size < 256) stack.add(ctm to state.copy())
                "Q" -> stack.removeLastOrNull()?.let { (m, s) ->
                    ctm = m
                    state = s
                }

                "cm" -> if (operands.size >= 6) ctm = matrixOf(operands).times(ctm)
                "re" -> if (operands.size >= 4) {
                    val x = num(operands, 0)
                    val y = num(operands, 1)
                    val rect = Rect.of(ctm, x, y, x + num(operands, 2), y + num(operands, 3))
                    pathBounds = pathBounds?.union(rect) ?: rect
                }

                "m", "l", "c", "v", "y" -> pathIsRects = false
                "W", "W*" -> pendingClip = true
                "n", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*" -> {
                    // 非矩形的裁剪路径不处理，宁可多提取也不误删
                    val bounds = pathBounds
                    if (pendingClip && pathIsRects && bounds != null) state.clip = state.clip?.intersect(bounds) ?: bounds
                    pathBounds = null
                    pathIsRects = true
                    pendingClip = false
                }

                "BT" -> {
                    tm = Matrix.IDENTITY
                    tlm = Matrix.IDENTITY
                }

                "ET" -> {}
                "Tf" -> {
                    state.fontSize = num(operands, 1)
                    state.font = font(fonts?.get((operands.getOrNull(0) as? PdfName)?.name ?: ""))
                }

                "Tc" -> state.charSpacing = num(operands, 0)
                "Tw" -> state.wordSpacing = num(operands, 0)
                "Tz" -> state.horizontalScale = num(operands, 0) / 100f
                "TL" -> state.leading = num(operands, 0)
                "Ts" -> state.rise = num(operands, 0)
                "Td" -> moveLine(num(operands, 0), num(operands, 1))
                "TD" -> {
                    state.leading = -num(operands, 1)
                    moveLine(num(operands, 0), num(operands, 1))
                }

                "Tm" -> if (operands.size >= 6) {
                    tlm = matrixOf(operands)
                    tm = tlm
                }

                "T*" -> moveLine(0f, -state.leading)
                "Tj" -> (operands.lastOrNull() as? PdfString)?.let { show(it.bytes) }
                "'" -> {
                    moveLine(0f, -state.leading)
                    (operands.lastOrNull() as? PdfString)?.let { show(it.bytes) }
                }

                "\"" -> {
                    state.wordSpacing = num(operands, 0)
                    state.charSpacing = num(operands, 1)
                    moveLine(0f, -state.leading)
                    (operands.lastOrNull() as? PdfString)?.let { show(it.bytes) }
                }

                "TJ" -> (operands.lastOrNull() as? PdfArray)?.items?.forEach { item ->
                    when (item) {
                        is PdfString -> show(item.bytes)
                        is PdfNumber -> adjust(item.float)
                        else -> {}
                    }
                }

                "Do" -> {
                    val name = (operands.lastOrNull() as? PdfName)?.name ?: return
                    val xObject = file.resolve(xObjects?.get(name)) as? PdfStream ?: return
                    if (isHidden(xObject.dict["OC"])) return
                    when ((xObject.dict["Subtype"] as? PdfName)?.name) {
                        "Form" -> runForm(xObject, resources, ctm, depth + 1)
                        "Image" -> if (actualText != null) actualTextAnchor = ctm.apply(0f, 1f)
                    }
                }

                "BMC" -> markedContent.add(0)
                "BDC" -> {
                    val tag = (operands.firstOrNull() as? PdfName)?.name
                    val props = when (val p = operands.lastOrNull()) {
                        is PdfDict -> p
                        is PdfName -> file.resolve(properties?.get(p.name)) as? PdfDict
                        else -> null
                    }
                    var flags = 0
                    if (tag == "OC" && isHidden(props)) {
                        flags = flags or MC_HIDDEN
                        hiddenDepth++
                    }
                    val text = (file.resolve(props?.get("ActualText")) as? PdfString)?.text()
                    if (text != null && actualText == null) {
                        actualText = text
                        actualTextEmitted = false
                        actualTextAnchor = null
                        flags = flags or MC_ACTUAL_TEXT
                    }
                    markedContent.add(flags)
                }

                "EMC" -> {
                    val flags = markedContent.removeLastOrNull() ?: 0
                    if (flags and MC_HIDDEN != 0) hiddenDepth--
                    if (flags and MC_ACTUAL_TEXT != 0) {
                        val text = actualText
                        val anchor = actualTextAnchor
                        if (!actualTextEmitted && text != null && anchor != null && hiddenDepth == 0) {
                            emitText(text, anchor.first, anchor.second + 12f, 10f)
                        }
                        actualText = null
                    }
                }
            }
        }

        /** 直接输出一段文字（无外观流的注释），从 (x, top) 开始逐行向下 */
        fun emitText(text: String, x: Float, top: Float, size: Float) {
            text.lines().forEachIndexed { i, line ->
                if (line.isBlank()) return@forEachIndexed
                val y = top - size * 1.2f * (i + 1)
                sink.add(line, x, y, x + line.length * size * 0.5f, y, size, 1f, 0f)
            }
        }

        /** 以 [base] 为当前变换矩阵绘制 Form XObject */
        fun runForm(xObject: PdfStream, parentResources: PdfDict?, base: Matrix, depth: Int) {
            // matplotlib 等导出的多子图 PDF 会把子图逐层嵌套，深度可达数十层
            if (depth >= 100 || formRuns >= 20_000 || !formStack.add(xObject)) return
            formRuns++
            try {
                val data = file.decodeStream(xObject) ?: return
                val formResources = file.resolve(xObject.dict["Resources"]) as? PdfDict ?: parentResources
                val matrix = (file.resolve(xObject.dict["Matrix"]) as? PdfArray)?.let { matrixOf(it.items) } ?: Matrix.IDENTITY
                val savedCtm = ctm
                val savedState = state.copy()
                val savedTm = tm
                val savedTlm = tlm
                val savedStack = stack.size
                ctm = matrix.times(base)
                // 表单只显示 BBox 内的内容，插图常用它截取源页面的一部分
                (file.resolve(xObject.dict["BBox"]) as? PdfArray)?.items?.let { box ->
                    val n = box.map { (file.resolve(it) as? PdfNumber)?.float ?: return@let }
                    if (n.size < 4) return@let
                    val bounds = Rect.of(ctm, n[0], n[1], n[2], n[3])
                    state.clip = state.clip?.intersect(bounds) ?: bounds
                }
                execute(data, formResources, depth)
                while (stack.size > savedStack) stack.removeAt(stack.lastIndex)
                ctm = savedCtm
                state = savedState
                tm = savedTm
                tlm = savedTlm
            } finally {
                formStack.remove(xObject)
            }
        }

        private fun moveLine(tx: Float, ty: Float) {
            tlm = Matrix(1f, 0f, 0f, 1f, tx, ty).times(tlm)
            tm = tlm
        }

        private fun adjust(amount: Float) {
            val shift = -amount / 1000f * state.fontSize
            tm = if (state.font?.vertical == true) {
                Matrix(1f, 0f, 0f, 1f, 0f, shift).times(tm)
            } else {
                Matrix(1f, 0f, 0f, 1f, shift * state.horizontalScale, 0f).times(tm)
            }
        }

        private fun show(bytes: ByteArray) {
            val font = state.font ?: return
            val fs = state.fontSize
            val th = state.horizontalScale
            val vertical = font.vertical
            for (glyph in font.decode(bytes)) {
                val m = tm.times(ctm)
                val spacing = state.charSpacing + if (glyph.wordSpace) state.wordSpacing else 0f
                val (ox, oy) = m.apply(0f, state.rise)
                val ex: Float
                val ey: Float
                if (vertical) {
                    val ty = -fs + spacing
                    m.apply(0f, ty).let { ex = it.first; ey = it.second }
                    tm = Matrix(1f, 0f, 0f, 1f, 0f, ty).times(tm)
                } else {
                    val tx = (glyph.width * fs + spacing) * th
                    m.apply(tx, state.rise).let { ex = it.first; ey = it.second }
                    tm = Matrix(1f, 0f, 0f, 1f, tx, 0f).times(tm)
                }
                val size = abs(fs) * sqrt(m.c * m.c + m.d * m.d)
                // 小到看不见的字（如 LaTeXiT 在图里嵌入的 base64 源码）不输出
                if (size > 0f && size < 0.1f) continue
                // 方向：水平文字取 x 轴，竖排文字沿 y 轴向下
                var dx = if (vertical) -m.c else m.a
                var dy = if (vertical) -m.d else m.b
                val len = sqrt(dx * dx + dy * dy)
                if (len > 0f) {
                    dx /= len
                    dy /= len
                } else {
                    dx = 1f
                    dy = 0f
                }
                // 与阅读器一致：字形框（基线上从起点到终点、高一个字号）完全在裁剪区域外时才丢弃
                val clip = state.clip
                if (clip != null) {
                    val ux = -dy * size
                    val uy = dx * size
                    val glyphBox = Rect(
                        min(min(ox, ex), min(ox, ex) + ux),
                        min(min(oy, ey), min(oy, ey) + uy),
                        max(max(ox, ex), max(ox, ex) + ux),
                        max(max(oy, ey), max(oy, ey) + uy),
                    )
                    // 步进宽度不等于字形轮廓（如负字距），留一些余量
                    if (!clip.intersects(glyphBox, margin = size * 0.25f)) continue
                }
                val text = when {
                    actualText == null -> glyph.unicode
                    actualTextEmitted -> ""
                    else -> actualText!!.also { actualTextEmitted = true }
                }
                if (hiddenDepth == 0) sink.add(text, ox, oy, ex, ey, if (size > 0f) size else 1f, dx, dy)
            }
        }

    }
}

/** 设备空间中的轴对齐矩形 */
internal data class Rect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    fun union(o: Rect) = Rect(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))

    // 不相交时得到空矩形（max < min），其中的内容都会被裁掉
    fun intersect(o: Rect) = Rect(max(minX, o.minX), max(minY, o.minY), min(maxX, o.maxX), min(maxY, o.maxY))

    fun intersects(o: Rect, margin: Float) =
        o.maxX + margin > minX && o.minX - margin < maxX && o.maxY + margin > minY && o.minY - margin < maxY

    companion object {
        /** 用户空间矩形 (x0, y0)-(x1, y1) 经 [m] 变换后的外接矩形 */
        fun of(m: Matrix, x0: Float, y0: Float, x1: Float, y1: Float): Rect {
            val corners = listOf(m.apply(x0, y0), m.apply(x1, y0), m.apply(x0, y1), m.apply(x1, y1))
            return Rect(
                corners.minOf { it.first },
                corners.minOf { it.second },
                corners.maxOf { it.first },
                corners.maxOf { it.second },
            )
        }
    }
}

internal data class Matrix(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    // this × other（PDF 行向量约定）
    fun times(o: Matrix) = Matrix(
        a * o.a + b * o.c,
        a * o.b + b * o.d,
        c * o.a + d * o.c,
        c * o.b + d * o.d,
        e * o.a + f * o.c + o.e,
        e * o.b + f * o.d + o.f,
    )

    fun apply(x: Float, y: Float): Pair<Float, Float> = Pair(x * a + y * c + e, x * b + y * d + f)

    companion object {
        val IDENTITY = Matrix(1f, 0f, 0f, 1f, 0f, 0f)
    }
}

private const val MC_ACTUAL_TEXT = 1
private const val MC_HIDDEN = 2

internal fun interface GlyphSink {
    /** 字形起点 (ox, oy)、终点 (ex, ey)、字号、书写方向单位向量 (dx, dy)，均为设备空间坐标 */
    fun add(text: String, ox: Float, oy: Float, ex: Float, ey: Float, glyphSize: Float, dx: Float, dy: Float)
}

/**
 * 把字形流合并成行：同一基线且位置连续的字形属于同一行，间隙足够大时插入空格，
 * 行距明显变大或回到上方时插入空行。
 */
internal class LineBuilder : GlyphSink {
    private val out = StringBuilder()
    private val line = StringBuilder()
    private var hasLine = false
    private var endX = 0f
    private var endY = 0f
    private var baseX = 0f
    private var baseY = 0f
    private var dirX = 1f
    private var dirY = 0f
    private var size = 0f
    private var lastLineBaseX = 0f
    private var lastLineBaseY = 0f
    private var lastLineSize = 0f
    private var hasLastLine = false

    // 本行最后一个字形的起点
    private var lastX = 0f
    private var lastY = 0f
    private var lastCjk = false

    // 本行是否由竖向堆叠的中日韩字形组成
    private var stacked = false

    // 本行各字形在书写方向上的起止位置，含从右到左文字时据此还原视觉顺序
    private class Item(val text: String, val start: Float, val end: Float)

    private val items = ArrayList<Item>()

    // 最近字形位置，用于去掉重复绘制（伪粗体、阴影）
    private val recent = ArrayDeque<RecentGlyph>()

    private class RecentGlyph(val text: String, val x: Float, val y: Float)

    override fun add(text: String, ox: Float, oy: Float, ex: Float, ey: Float, glyphSize: Float, dx: Float, dy: Float) {
        if (text.isEmpty()) {
            // 无法映射的字形仍推进位置
            if (hasLine) {
                endX = ex
                endY = ey
            }
            return
        }
        if (hasLine) {
            val sameDir = dx * dirX + dy * dirY > 0.95f
            val relX = ox - endX
            val relY = oy - endY
            val gap = relX * dirX + relY * dirY
            val perp = -relX * dirY + relY * dirX
            val tolerance = maxOf(size, glyphSize)
            if (isDuplicate(text, ox, oy, tolerance)) return
            val cjk = isCjk(text)
            // 允许最多一个字宽的回退：全角标点挤压、重音符号等会让下一个字形略微左移
            if (sameDir && abs(perp) < tolerance * 0.5f && gap > -tolerance && !stacked) {
                if (gap > tolerance * 0.15f && !line.endsWith(' ') && !text.startsWith(' ')) line.append(' ')
                append(text, ox, oy, ex, ey, glyphSize, cjk)
                return
            }
            if (sameDir && abs(perp) < tolerance * 0.5f) {
                // 紧贴在上一个字形左侧：按逻辑顺序绘制的从右到左文字
                val back = (lastX - ex) * dirX + (lastY - ey) * dirY
                if (back > -tolerance * 0.5f && back < tolerance * 0.6f && !stacked) {
                    if (back > tolerance * 0.15f && !line.endsWith(' ') && !text.startsWith(' ')) line.append(' ')
                    items.add(Item(appendText(text), along(ox, oy), along(ex, ey)))
                    lastX = ox
                    lastY = oy
                    remember(text, ox, oy)
                    return
                }
            }
            if (sameDir && cjk && lastCjk && (stacked || line.length <= 2)) {
                // 横排字形逐个向下堆叠的竖排中日韩文字（如浏览器打印的 writing-mode: vertical-rl）
                val along = (ox - lastX) * dirX + (oy - lastY) * dirY
                val down = (ox - lastX) * dirY - (oy - lastY) * dirX
                if (abs(along) < tolerance * 0.3f && down > tolerance * 0.6f && down < tolerance * 1.6f) {
                    stacked = true
                    append(text, ox, oy, ex, ey, glyphSize, cjk)
                    return
                }
            }
            flushLine()
        }
        hasLine = true
        baseX = ox
        baseY = oy
        endX = ex
        endY = ey
        dirX = dx
        dirY = dy
        size = glyphSize
        stacked = false
        items.clear()
        append(text, ox, oy, ex, ey, glyphSize, isCjk(text))
    }

    private fun along(x: Float, y: Float): Float = (x - baseX) * dirX + (y - baseY) * dirY

    private fun append(text: String, ox: Float, oy: Float, ex: Float, ey: Float, glyphSize: Float, cjk: Boolean) {
        items.add(Item(appendText(text), along(ox, oy), along(ex, ey)))
        endX = ex
        endY = ey
        lastX = ox
        lastY = oy
        lastCjk = cjk
        if (glyphSize > size) size = glyphSize
        remember(text, ox, oy)
    }

    private fun isCjk(text: String): Boolean {
        val cp = text.codePointAt(0)
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
            // 全角标点
            else -> cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF
        }
    }

    private fun isDuplicate(text: String, x: Float, y: Float, size: Float): Boolean {
        val eps = size * 0.15f
        return recent.any { it.text == text && abs(it.x - x) < eps && abs(it.y - y) < eps }
    }

    private fun remember(text: String, x: Float, y: Float) {
        recent.addLast(RecentGlyph(text, x, y))
        if (recent.size > 48) recent.removeFirst()
    }

    // 追加规范化后的文字，返回实际追加的内容
    private fun appendText(text: String): String {
        val before = line.length
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ' -> if (!line.endsWith(' ')) line.append(' ')
                ch == '\u00AD' -> {} // 软连字符
                ch in '\uFB00'..'\uFB06' -> line.append(LIGATURES[ch - '\uFB00'])
                // 康熙部首与兼容汉字：字体常把它们和统一汉字映射到同一字形，ToUnicode 可能选错
                ch in '\u2F00'..'\u2FD5' || ch in '\uF900'..'\uFAFF' -> line.append(Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC))
                ch in '\u2E80'..'\u2EFF' && RADICALS_SUPPLEMENT[ch - '\u2E80'] != 0 -> line.appendCodePoint(RADICALS_SUPPLEMENT[ch - '\u2E80'])
                // 阿拉伯文表现形式（连写变体）还原为基本字母
                ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF' && ch != '\uFEFF' -> line.append(Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC))
                ch < ' ' || ch == '\uFFFD' || ch == '\uFEFF' -> {}
                else -> line.append(ch)
            }
        }
        return line.substring(before)
    }

    private fun flushLine() {
        if (!hasLine) return
        var text = line.toString().trimEnd()
        if (hasRtl(text)) text = logicalOrder()
        if (text.isNotBlank()) {
            if (hasLastLine) {
                // 用上一行基线到本行基线在行法线方向上的距离判断段落
                val relX = baseX - lastLineBaseX
                val relY = baseY - lastLineBaseY
                val down = relX * dirY - relY * dirX
                val ref = maxOf(lastLineSize, size)
                out.append('\n')
                if (down > ref * 1.9f || down < -ref * 0.5f) out.append('\n')
            }
            out.append(text)
            lastLineBaseX = baseX
            lastLineBaseY = baseY
            lastLineSize = size
            hasLastLine = true
        }
        line.setLength(0)
        hasLine = false
    }

    /**
     * 含从右到左文字的行：先按位置排出视觉顺序，再以字形为单位做 Bidi 重排还原逻辑顺序。
     * 以字形为单位是为了不打乱单个字形内部已是逻辑顺序的文字（连字的 ToUnicode、注释文字等）。
     */
    private fun logicalOrder(): String {
        val sorted = items.filter { it.text.isNotEmpty() }.sortedBy { it.start }
        // 视觉顺序中的空格也作为单元参与重排
        val units = ArrayList<String>()
        var prevEnd = Float.NaN
        for (item in sorted) {
            if (!prevEnd.isNaN() && item.start - prevEnd > size * 0.15f && units.lastOrNull() != " " && !item.text.startsWith(' ')) units.add(" ")
            units.add(item.text)
            prevEnd = if (prevEnd.isNaN()) item.end else maxOf(prevEnd, item.end)
        }
        val visual = units.joinToString("")
        val bidi = Bidi(visual, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return visual.trimEnd()
        // 每个单元取其首个强方向字符的嵌入层级
        val levels = ByteArray(units.size)
        var offset = 0
        units.forEachIndexed { i, unit ->
            var level = bidi.getLevelAt(offset)
            for (k in unit.indices) {
                val d = Character.getDirectionality(unit[k])
                if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    level = bidi.getLevelAt(offset + k)
                    break
                }
            }
            levels[i] = level.toByte()
            offset += unit.length
        }
        val array = units.toTypedArray<Any>()
        Bidi.reorderVisually(levels, 0, array, 0, array.size)
        return array.joinToString("").trimEnd()
    }

    fun finish(): String {
        flushLine()
        return out.toString()
    }

    companion object {
        private val LIGATURES = arrayOf("ff", "fi", "fl", "ffi", "ffl", "st", "st")

        private fun hasRtl(text: String): Boolean = text.any {
            val d = Character.getDirectionality(it)
            d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }

        // CJK 部首补充区 U+2E80-2EFF → 统一汉字，来自 Unicode EquivalentUnifiedIdeograph.txt，0 表示无对应
        private val RADICALS_SUPPLEMENT = intArrayOf(
            0, 0x5382, 0x4E5B, 0x4E5A, 0x4E59, 0x4EBB, 0x5182, 0x20628, 0x5200, 0x5202, 0x535C, 0x353E,
            0, 0, 0x5140, 0x5C23, 0x5C22, 0x21BC2, 0x5DF3, 0x5E7A, 0x5F51, 0x2B739, 0x5FC4, 0x5FC3,
            0x624C, 0x6535, 0, 0x65E1, 0x65E5, 0x6708, 0x6B7A, 0x6BCD, 0x6C11, 0x6C35, 0x6C3A, 0x706C,
            0, 0, 0x4E2C, 0x725B, 0x72AD, 0x738B, 0x24D14, 0x76EE, 0x793A, 0x793B, 0x25AD7, 0x7CF9,
            0x7E9F, 0x7F53, 0x7F52, 0x34C1, 0x5197, 0x2626B, 0x7F8A, 0x2634C, 0x2634B, 0x8002, 0x8080, 0x807F,
            0x8089, 0x26951, 0, 0, 0, 0x864E, 0x8864, 0x8980, 0x897F, 0x89C1, 0x89D2, 0x278B2,
            0x8BA0, 0x8D1D, 0x27FB7, 0x8F66, 0, 0, 0, 0x9091, 0x9485, 0x9577, 0x9578, 0x957F,
            0x95E8, 0x28E0F, 0x961D, 0x96E8, 0x9752, 0x97E6, 0x9875, 0x98CE, 0x98DE, 0x98DF, 0x2967F, 0x98E0,
            0x9963, 0x29810, 0x9A6C, 0x9AA8, 0x9B3C, 0x9C7C, 0x9E1F, 0x5364, 0x9EA6, 0x9EC4, 0x9EFE, 0x6589,
            0x9F50, 0x6B6F, 0x9F7F, 0x7ADC, 0x9F99, 0x9F9C, 0x4E80, 0x9F9F, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
    }
}
