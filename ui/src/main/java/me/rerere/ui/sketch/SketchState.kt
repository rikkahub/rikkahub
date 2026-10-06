package me.rerere.ui.sketch

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

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

/** 画板上的一步操作，撤销和重做以它为单位。 */
internal sealed interface SketchStep {
    /**
     * 一笔。坐标和线宽都以画纸为准，原点在画纸正中，和画纸在屏幕上显示得多大无关。
     * [erase] 的一笔是橡皮：不上色，而是把它经过的笔画擦掉。
     */
    class Line(val start: Offset, val color: Color, val width: Float, val erase: Boolean = false) : SketchStep {
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

    var width by mutableStateOf(SketchDefaults.BrushWidths[1])
    var erasing by mutableStateOf(false)
        private set

    // Path 的改动 Compose 观察不到，画的过程中靠它触发重绘
    var revision by mutableIntStateOf(0)
        private set

    /** 没有画任何东西。只垫了底图也算，那样确认出去的就是原图。 */
    val isBlank: Boolean get() = steps.isEmpty() || steps.last() is SketchStep.Clear

    // 撤销掉的笔画还能重做回来，它们的坐标也是按现在的画纸算的
    private val hasSteps: Boolean get() = steps.isNotEmpty() || undone.isNotEmpty()
    val canUndo: Boolean get() = steps.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    // 选颜色就是要接着画，橡皮跟着收起来
    fun usePreset(color: Color) {
        this.color = color
        usingCustomColor = false
        erasing = false
        pickingColor = false
    }

    fun useEraser(enabled: Boolean) {
        erasing = enabled
        pickingColor = false
    }

    fun useCustom(color: Color) {
        customColor = color
        this.color = color
        usingCustomColor = true
        erasing = false
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

    fun begin(point: Offset, width: Float): SketchStep.Line {
        val line = if (erasing) {
            SketchStep.Line(point, Color.Black, width * ERASER_WIDTH_SCALE, erase = true)
        } else {
            SketchStep.Line(point, color, width)
        }
        steps += line
        undone.clear()
        // 落笔说明颜色调好了
        pickingColor = false
        return line
    }

    fun extend(line: SketchStep.Line, point: Offset) {
        line.extendTo(point)
        revision++
    }

    fun finish(line: SketchStep.Line) {
        line.finish()
        revision++
    }

    fun clear() {
        if (isBlank) return
        steps += SketchStep.Clear
        undone.clear()
    }

    fun undo() {
        steps.removeLastOrNull()?.let { undone += it }
    }

    fun redo() {
        undone.removeLastOrNull()?.let { steps += it }
    }
}

/** 在这块区域里按 [aspectRatio] 能放下的最大尺寸。 */
internal fun Size.fit(aspectRatio: Float): Size =
    if (width / height > aspectRatio) Size(height * aspectRatio, height) else Size(width, width / aspectRatio)
