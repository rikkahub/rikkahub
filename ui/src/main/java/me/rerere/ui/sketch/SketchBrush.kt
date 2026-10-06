package me.rerere.ui.sketch

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.BinaryOpNode
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.SourceNode.Source
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.TargetNode.Target
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.brush.behavior.ValueNode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DashedLine01
import me.rerere.hugeicons.stroke.Highlighter
import me.rerere.hugeicons.stroke.PaintBrush01
import me.rerere.hugeicons.stroke.Pen01
import me.rerere.hugeicons.stroke.PenTool03
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.ui.R

// 笔画轮廓的精度，单位是画纸坐标。画纸坐标和屏幕像素差不多大，十分之一个像素足够平滑
private const val BRUSH_EPSILON = 0.1f

/**
 * 画笔的种类。
 *
 * @param widthScale 画出来的宽度是所选粗细的多少倍
 * @param alpha 颜色的不透明度
 */
internal enum class SketchBrush(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    private val widthScale: Float = 1f,
    private val alpha: Float = 1f,
) {
    /** 触控笔按压力变粗细；手指没有压力，画出来是收尾略细的均匀线条。 */
    Pen(R.string.sketch_dialog_brush_pen, HugeIcons.QuillWrite01),

    /** 从头到尾一样粗。 */
    Marker(R.string.sketch_dialog_brush_marker, HugeIcons.Pen01),

    /** 平头笔：笔尖是一道斜着的扁口，顺着扁口走的线细，横过去的线粗。 */
    Flat(R.string.sketch_dialog_brush_flat, HugeIcons.PenTool03, widthScale = 2.5f),

    /** 毛笔：起笔收笔带锋，粗细变化大。触控笔按压力，手指按快慢。 */
    Calligraphy(R.string.sketch_dialog_brush_calligraphy, HugeIcons.PaintBrush01, widthScale = 2.5f),

    /** 半透明的宽笔，涂过的地方下面的内容还看得见，用来在图上标重点。 */
    Highlighter(R.string.sketch_dialog_brush_highlighter, HugeIcons.Highlighter, widthScale = 4f, alpha = 0.4f),

    /** 虚线。 */
    Dashed(R.string.sketch_dialog_brush_dashed, HugeIcons.DashedLine01);

    private val family: BrushFamily
        get() = when (this) {
            Pen -> StockBrushes.pressurePen()
            Marker -> StockBrushes.marker()
            Flat -> FlatFamily
            Calligraphy -> CalligraphyFamily
            // 一笔自己交叠的地方不叠深。默认的做法在屏幕上会叠深、导出时不会，两边就对不上了
            Highlighter -> StockBrushes.highlighter(SelfOverlap.DISCARD)
            Dashed -> StockBrushes.dashedLine()
        }

    /** [width] 是所选的粗细，单位是画纸坐标。 */
    fun create(color: Color, width: Float): Brush = Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = color.copy(alpha = alpha).toArgb(),
        size = width * widthScale,
        epsilon = BRUSH_EPSILON,
    )
}

private fun size(from: Float, to: Float, input: ValueNode) =
    BrushBehavior(TargetNode(Target.SIZE_MULTIPLIER, from, to, input))

private val FlatFamily: BrushFamily by lazy {
    BrushFamily(tip = BrushTip(scaleX = 0.2f, scaleY = 1f, cornerRounding = 0.2f, rotationDegrees = 45f))
}

// Ink 没有预置的毛笔，这里用几条“笔尖大小跟着输入变”的规则拼一支。几条规则的倍数是相乘的
private val CalligraphyFamily: BrushFamily by lazy {
    // 粗细跟得太紧线条会抖，按时间平滑一下
    fun smooth(seconds: Float, input: ValueNode) = DampingNode(ProgressDomain.TIME_IN_SECONDS, seconds, input)

    // 先快后慢地变，笔锋是鼓出来的弧形而不是削出来的直边
    fun eased(input: ValueNode) = ResponseNode(EasingFunction.Predefined.EASE_OUT, input)

    BrushFamily(
        tip = BrushTip(
            behaviors = listOf(
                // 起笔：落笔时略收，很快铺开
                size(0.6f, 1f, eased(SourceNode(Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 1f))),
                // 收笔：提笔出锋。刚落笔的那一小段不收，不然点一下出来的点小得看不见
                size(
                    from = 0.12f,
                    to = 1f,
                    input = eased(
                        BinaryOpNode(
                            BinaryOpNode.BinaryOp.MAX,
                            SourceNode(Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 2.5f),
                            SourceNode(Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 2f, 0f),
                        ),
                    ),
                ),
                // 触控笔：按得越重越粗
                size(
                    from = 0.3f,
                    to = 1.3f,
                    input = smooth(
                        seconds = 0.03f,
                        input = ToolTypeFilterNode(
                            setOf(InputToolType.STYLUS),
                            SourceNode(Source.NORMALIZED_PRESSURE, 0f, 1f),
                        ),
                    ),
                ),
                // 手指没有压力：走得越快越细，像提着笔带过去
                size(
                    from = 1.2f,
                    to = 0.45f,
                    input = smooth(
                        seconds = 0.08f,
                        input = ToolTypeFilterNode(
                            setOf(InputToolType.TOUCH, InputToolType.MOUSE, InputToolType.UNKNOWN),
                            SourceNode(Source.SPEED_IN_CENTIMETERS_PER_SECOND, 3f, 30f),
                        ),
                    ),
                ),
            ),
        ),
    )
}
