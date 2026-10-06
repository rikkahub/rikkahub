package me.rerere.ui.sketch

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import kotlin.math.ceil
import kotlin.math.min

internal object SketchDefaults {
    // 画纸固定是白色的：导出的图片不随应用的深浅色主题变化
    val PaperColor = Color.White

    val BrushColors = listOf(
        Color(0xFF000000),
        Color(0xFFE53935),
        Color(0xFFFB8C00),
        Color(0xFF43A047),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA),
    )

    // 色相转一圈，用在自定义颜色的色块和色相滑杆上
    val HueColors = List(7) { Color.hsv(it * 60f, 1f, 1f) }

    // 第一次打开调色面板时的起始色：鲜艳一点，三条滑杆一拖就能看到变化
    val CustomColor = Color(0xFFE91E63)

    val BrushWidths = listOf(3.dp, 6.dp, 12.dp)

    // 空白画纸可选的宽高比
    val AspectRatios = listOf(1 to 1, 4 to 3, 3 to 4, 16 to 9, 9 to 16)
}

// 橡皮比同一档的画笔粗
private const val ERASER_WIDTH_SCALE = 4f

// 字号是所选粗细的多少倍
private const val TEXT_SIZE_SCALE = 4f

/** 手指落在画纸上时做什么。 */
internal enum class SketchTool { Brush, Eraser, Text, Select }

/** 画板上的一步操作，撤销和重做以它为单位。 */
internal sealed interface SketchStep {
    /** 画纸上可以选中、挪动和缩放的东西：笔画和文字。 */
    sealed interface Item : SketchStep {
        /** 落下时占的范围，之后的挪动和缩放不算在内。 */
        val bounds: Rect

        /** 是不是碰到了 [area]。[area] 和 [bounds] 一样是落下时的坐标。 */
        fun touches(area: Rect): Boolean
    }

    /** 画笔的一笔。坐标和粗细都以画纸为准，原点在画纸正中，和画纸在屏幕上显示得多大无关。 */
    class Ink(val stroke: Stroke) : Item {
        override val bounds: Rect =
            stroke.shape.computeBoundingBox()?.let { Rect(it.xMin, it.yMin, it.xMax, it.yMax) } ?: Rect.Zero

        // 一条斜线占的范围是一大块，光看范围的话在它旁边的空白处拉框也会选中它，要看笔画本身碰没碰到
        override fun touches(area: Rect): Boolean = bounds.overlaps(area) &&
            stroke.shape.computeCoverageIsGreaterThan(
                ImmutableBox.fromTwoPoints(ImmutableVec(area.left, area.top), ImmutableVec(area.right, area.bottom)),
                0f,
            )
    }

    /** 把 [items] 一起挪动、缩放了一次。它们本身不变，画的时候再把经历过的变换叠上去，撤销就是少叠这一次。 */
    class Transform(val items: List<Item>, val placement: SketchPlacement) : SketchStep

    /** 橡皮的一笔：不上色，而是把它经过的笔画擦掉。坐标和 [Ink] 一样以画纸为准。 */
    class Erase(val start: Offset, val width: Float) : SketchStep {
        val path = Path().apply { moveTo(start.x, start.y) }
        private var last = start

        /** 没有移动过的一笔是一个点。 */
        var moved = false
            private set

        fun extendTo(point: Offset) {
            // 以上一个点为控制点连到两点的中点，折线画出来是圆滑的
            val middle = (last + point) / 2f
            path.quadraticTo(last.x, last.y, middle.x, middle.y)
            last = point
            moved = true
        }

        /** 抬笔时把停在中点的线补到最后一个点。 */
        fun finish() {
            if (moved) path.lineTo(last.x, last.y)
        }
    }

    /**
     * 一段文字。位置和字号都以画纸为准。
     *
     * @param start 第一行左端的中点
     * @param maxWidth 超过这个宽度就换行
     */
    class Text(val text: String, start: Offset, color: Color, size: Float, maxWidth: Float) : Item {
        // 排版只做这一次，屏幕上和导出时画的是同一份，换行的位置才不会不一样
        val layout: StaticLayout = run {
            // 排好的字会被整体缩放着画出来，字距要能跟着等比例缩放
            val flags = TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG or TextPaint.LINEAR_TEXT_FLAG
            val paint = TextPaint(flags).apply {
                textSize = size
                this.color = color.toArgb()
            }
            // 没到宽度上限时只占文字本身那么宽
            val width = ceil(min(maxWidth, Layout.getDesiredWidth(text, paint))).toInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
        }

        /** 文字左上角的位置。 */
        val position = Offset(start.x, start.y - layout.getLineBottom(0) / 2f)

        override val bounds = Rect(position, Size(layout.width.toFloat(), layout.height.toFloat()))

        override fun touches(area: Rect): Boolean = bounds.overlaps(area)
    }

    /** 清空画纸。它也是一步操作，可以撤销。 */
    data object Clear : SketchStep
}

@Stable
internal class SketchState(aspectRatio: Float? = null) {
    // 屏幕上留给画纸的区域，画纸按自己的比例在里面放到最大
    private var available = Size.Unspecified

    /** 画纸的尺寸：落笔之前跟着可用区域走，落笔之后就定下来，区域再变化（比如旋转屏幕）只是把画纸整体缩放。 */
    var paperSize by mutableStateOf(Size.Unspecified)
        private set

    /** 空白画纸的宽高比，空表示占满可用区域。垫了底图时以底图的比例为准。 */
    var aspectRatio by mutableStateOf(aspectRatio)
        private set

    /** 垫在笔画下面的图片。 */
    var background by mutableStateOf<ImageBitmap?>(null)
        private set

    val steps = mutableStateListOf<SketchStep>()
    private val undone = mutableStateListOf<SketchStep>()

    var color by mutableStateOf(SketchDefaults.BrushColors.first())
        private set

    /** 自己调出来的颜色，还没调过时为空。 */
    var customColor by mutableStateOf<Color?>(null)
        private set

    // 调出来的颜色可能和某个预设色一样，选中的是哪一个要单独记
    var usingCustomColor by mutableStateOf(false)
        private set

    /** 调色面板是否打开。 */
    var pickingColor by mutableStateOf(false)

    var brush by mutableStateOf(SketchBrush.Pen)
        private set

    var width by mutableStateOf(SketchDefaults.BrushWidths[1])

    var tool by mutableStateOf(SketchTool.Brush)
        private set
    val erasing: Boolean get() = tool == SketchTool.Eraser

    /** 点了画纸、正等着输入文字时，文字要放的位置（画纸坐标）。 */
    var writingAt by mutableStateOf<Offset?>(null)
        private set
    private var writingSize = 0f

    /** 选中的笔画和文字。 */
    var selection by mutableStateOf<List<SketchStep.Item>>(emptyList())
        private set

    /** 选中的东西正被拖着挪动或缩放、还没松手时的变换。 */
    var moving by mutableStateOf(SketchPlacement.None)

    /** 正在拉的选框（画纸坐标）。 */
    var marquee by mutableStateOf<Rect?>(null)

    // Path 的改动 Compose 观察不到，擦的过程中靠它触发重绘
    var revision by mutableIntStateOf(0)
        private set

    /** 没有画任何东西。只垫了底图也算，那样确认出去的就是原图。 */
    val isBlank: Boolean get() = steps.isEmpty() || steps.last() is SketchStep.Clear

    // 撤销掉的笔画还能重做回来，它们的坐标也是按现在的画纸算的
    private val hasSteps: Boolean get() = steps.isNotEmpty() || undone.isNotEmpty()
    val canUndo: Boolean get() = steps.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    // 选颜色就是要接着画，橡皮跟着收起来。正在写字的话还是写字，文字用的也是这个颜色
    fun usePreset(color: Color) {
        this.color = color
        usingCustomColor = false
        if (erasing) tool = SketchTool.Brush
        pickingColor = false
    }

    fun useBrush(brush: SketchBrush) {
        this.brush = brush
        use(SketchTool.Brush)
    }

    fun useEraser(enabled: Boolean) = use(if (enabled) SketchTool.Eraser else SketchTool.Brush)

    fun useText(enabled: Boolean) = use(if (enabled) SketchTool.Text else SketchTool.Brush)

    fun useSelect(enabled: Boolean) = use(if (enabled) SketchTool.Select else SketchTool.Brush)

    private fun use(tool: SketchTool) {
        this.tool = tool
        pickingColor = false
        deselect()
    }

    fun useCustom(color: Color) {
        customColor = color
        this.color = color
        usingCustomColor = true
        if (erasing) tool = SketchTool.Brush
    }

    fun fit(available: Size) {
        this.available = available
        if (!hasSteps) layout()
    }

    fun useAspectRatio(aspectRatio: Float?) {
        this.aspectRatio = aspectRatio
        layout()
    }

    fun useBackground(image: ImageBitmap?) {
        background = image
        layout()
    }

    // 按当前的比例重新定画纸的尺寸
    private fun layout() {
        if (available.isUnspecified) return
        val ratio = background?.let { it.width.toFloat() / it.height }
            ?: aspectRatio
            ?: (available.width / available.height)
        // 已经有笔画时保持它们在屏幕上的大小和位置：换比例只是以画纸正中为准重新裁出一块，裁到外面的笔画换回来还在
        val scale = if (hasSteps && paperSize.isSpecified) {
            available.fit(paperSize.width / paperSize.height).width / paperSize.width
        } else {
            1f
        }
        paperSize = available.fit(ratio) / scale
    }

    /** 画笔画完的笔画记下来。几根手指同时画的会一起交过来，每一笔各算一步。 */
    fun draw(strokes: List<Stroke>) {
        strokes.forEach { steps += SketchStep.Ink(it) }
        undone.clear()
    }

    /** 在 [point] 处开始写字，[width] 是所选的粗细。文字输入完再用 [write] 落到画纸上。 */
    fun beginText(point: Offset, width: Float) {
        writingAt = point
        writingSize = width * TEXT_SIZE_SCALE
    }

    fun write(text: String) {
        val start = writingAt ?: return
        writingAt = null
        if (text.isBlank()) return
        steps += SketchStep.Text(
            text = text.trim(),
            start = start,
            color = color,
            size = writingSize,
            // 写到画纸右边就换行，太靠边时也至少留出一个字的宽度
            maxWidth = (paperSize.width / 2 - start.x).coerceAtLeast(writingSize),
        )
        undone.clear()
    }

    fun cancelText() {
        writingAt = null
    }

    fun erase(point: Offset, width: Float): SketchStep.Erase {
        val line = SketchStep.Erase(point, width * ERASER_WIDTH_SCALE)
        steps += line
        undone.clear()
        return line
    }

    fun extend(line: SketchStep.Erase, point: Offset) {
        line.extendTo(point)
        revision++
    }

    fun finish(line: SketchStep.Erase) {
        line.finish()
        revision++
    }

    /** 画纸上现有的笔画和文字，从下到上，连同它们各自被挪动、缩放成了什么样。 */
    fun placements(): Map<SketchStep.Item, SketchPlacement> {
        val placements = LinkedHashMap<SketchStep.Item, SketchPlacement>()
        // 清空之前的已经看不到了
        for (index in steps.indexOfLast { it is SketchStep.Clear } + 1 until steps.size) {
            when (val step = steps[index]) {
                is SketchStep.Item -> placements[step] = SketchPlacement.None
                is SketchStep.Transform -> step.items.forEach { item ->
                    placements[item]?.let { placements[item] = it.then(step.placement) }
                }

                else -> {}
            }
        }
        // 正被拖着的也算上，松手之前画出来的就是最后的样子
        if (moving != SketchPlacement.None) {
            selection.forEach { item -> placements[item]?.let { placements[item] = it.then(moving) } }
        }
        return placements
    }

    /** 选中的东西合起来占的范围，没有选中时为空。 */
    val selectionBounds: Rect?
        get() {
            if (selection.isEmpty()) return null
            val placements = placements()
            return selection
                .mapNotNull { item -> placements[item]?.apply(item.bounds) }
                .reduceOrNull { all, next ->
                    Rect(
                        minOf(all.left, next.left),
                        minOf(all.top, next.top),
                        maxOf(all.right, next.right),
                        maxOf(all.bottom, next.bottom),
                    )
                }
        }

    /** 选中碰到 [area] 的笔画和文字。[topmost] 时只选最上面的一个，用在点一下的时候。 */
    fun select(area: Rect, topmost: Boolean) {
        val touched = placements().filter { (item, placement) -> item.touches(placement.invert(area)) }.keys.toList()
        selection = if (topmost) listOfNotNull(touched.lastOrNull()) else touched
    }

    fun deselect() {
        selection = emptyList()
        moving = SketchPlacement.None
        marquee = null
    }

    /** 松手：把拖出来的变换记成一步。 */
    fun settle() {
        val placement = moving
        moving = SketchPlacement.None
        if (placement == SketchPlacement.None || selection.isEmpty()) return
        steps += SketchStep.Transform(selection, placement)
        undone.clear()
    }

    fun clear() {
        if (isBlank) return
        deselect()
        steps += SketchStep.Clear
        undone.clear()
    }

    // 撤销和重做之后选中的东西可能已经不在了，一律取消选中
    fun undo() {
        deselect()
        steps.removeLastOrNull()?.let { undone += it }
    }

    fun redo() {
        deselect()
        undone.removeLastOrNull()?.let { steps += it }
    }
}

/** 在这块区域里按 [aspectRatio] 能放下的最大尺寸。 */
internal fun Size.fit(aspectRatio: Float): Size =
    if (width / height > aspectRatio) Size(height * aspectRatio, height) else Size(width, width / aspectRatio)
