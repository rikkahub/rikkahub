package me.rerere.ui.crop

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// 相对整张图刚好放进可用区域时，最多再放大几倍
private const val MAX_ZOOM = 8f

/**
 * 裁剪框的比例选项。只有没有固定比例时才能切换。
 */
internal sealed interface CropAspect {
    data object Free : CropAspect
    data object Original : CropAspect
    data class Ratio(val width: Int, val height: Int) : CropAspect

    companion object {
        val Options = listOf(
            Free,
            Original,
            Ratio(1, 1),
            Ratio(4, 3),
            Ratio(3, 4),
            Ratio(16, 9),
            Ratio(9, 16),
        )
    }
}

/**
 * 裁剪框上可以拖动的位置：四个角和四条边，记下它会带动哪几条边。
 */
internal enum class CropHandle(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    TopLeft(left = true, top = true),
    TopRight(top = true, right = true),
    BottomRight(right = true, bottom = true),
    BottomLeft(left = true, bottom = true),
    Left(left = true),
    Top(top = true),
    Right(right = true),
    Bottom(bottom = true);

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

/**
 * 裁剪的状态，坐标都是裁剪画布上的像素。
 *
 * 图片按 [quarterTurns] 顺时针转过之后，左上角放在 [offset]，每个原图像素画成 [scale] 个像素；
 * 裁剪框 [frame] 始终落在图片里面。拖图片是移动和缩放图片，拖框的边角是改框的大小，
 * 松手后框会连同图片一起放大回到中间。
 */
internal class ImageCropState(
    private val source: Bitmap,
    val preview: ImageBitmap,
    // 固定的宽高比，比如头像的 1:1；为空时可以自由裁剪，也可以选比例
    val lockedRatio: Float?,
) {
    val sourceWidth: Int get() = source.width
    val sourceHeight: Int get() = source.height

    var quarterTurns by mutableIntStateOf(0)
        private set
    var aspect: CropAspect by mutableStateOf(CropAspect.Free)
        private set
    var frame by mutableStateOf(Rect.Zero)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var scale by mutableFloatStateOf(1f)
        private set

    // 正在拖的时候显示三分线
    var interacting by mutableStateOf(false)
    var modified by mutableStateOf(false)
        private set

    // 裁剪框能占的区域，四周留出拖动边角的位置。画布在布局之前可能先画一帧，读它才能在布局后重画
    private var area by mutableStateOf(Rect.Zero)
    private var minFrameSize = 0f

    val isReady: Boolean get() = !area.isEmpty

    /** 转过之后的图片尺寸，单位是原图像素。 */
    val imageSize: Size
        get() = if (quarterTurns % 2 == 0) {
            Size(source.width.toFloat(), source.height.toFloat())
        } else {
            Size(source.height.toFloat(), source.width.toFloat())
        }

    val imageRect: Rect get() = Rect(offset, imageSize * scale)

    // 当前生效的宽高比，为空时随便拉
    val ratio: Float?
        get() = lockedRatio ?: when (val aspect = aspect) {
            CropAspect.Free -> null
            CropAspect.Original -> imageSize.width / imageSize.height
            is CropAspect.Ratio -> aspect.width.toFloat() / aspect.height
        }

    private val minScale: Float
        get() = max(frame.width / imageSize.width, frame.height / imageSize.height)

    private val maxScale: Float
        get() = max(
            min(area.width / imageSize.width, area.height / imageSize.height) * MAX_ZOOM,
            minScale,
        )

    /** 画布的尺寸变了：重新摆放，之前的裁剪不保留。 */
    fun layout(area: Rect, minFrameSize: Float) {
        if (area == this.area) return
        this.area = area
        this.minFrameSize = minFrameSize
        fit()
    }

    fun reset() {
        quarterTurns = 0
        aspect = CropAspect.Free
        modified = false
        fit()
    }

    fun rotate() {
        quarterTurns = (quarterTurns + 1) % 4
        modified = true
        fit()
    }

    fun selectAspect(aspect: CropAspect) {
        if (lockedRatio != null || aspect == this.aspect) return
        this.aspect = aspect
        modified = true
        // 换成自由比例时保留现在的框，只是不再锁比例
        if (aspect != CropAspect.Free) fit()
    }

    // 框按比例尽量撑满区域，图片刚好盖住框并居中
    private fun fit() {
        if (area.isEmpty) return
        val image = imageSize
        val ratio = ratio ?: (image.width / image.height)
        val width = min(area.width, area.height * ratio)
        val height = width / ratio
        frame = Rect(
            left = area.center.x - width / 2,
            top = area.center.y - height / 2,
            right = area.center.x + width / 2,
            bottom = area.center.y + height / 2,
        )
        scale = minScale
        offset = frame.center - Offset(image.width * scale / 2, image.height * scale / 2)
    }

    /** 拖动或者双指缩放图片：以 [centroid] 为中心缩放 [zoom] 倍，再平移 [pan]。 */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        offset = centroid - (centroid - offset) * (newScale / scale) + pan
        scale = newScale
        modified = true
        clampImage()
    }

    // 图片不能露出框外的空白
    private fun clampImage() {
        val size = imageSize * scale
        offset = Offset(
            x = offset.x.safeCoerceIn(frame.right - size.width, frame.left),
            y = offset.y.safeCoerceIn(frame.bottom - size.height, frame.top),
        )
    }

    /** 按下的位置落在框的哪个边角上，都不是时返回 null，表示拖的是图片。 */
    fun handleAt(position: Offset, touchRadius: Float): CropHandle? {
        val frame = frame
        val corners = listOf(
            CropHandle.TopLeft to frame.topLeft,
            CropHandle.TopRight to frame.topRight,
            CropHandle.BottomRight to frame.bottomRight,
            CropHandle.BottomLeft to frame.bottomLeft,
        )
        corners.minByOrNull { (_, corner) -> (corner - position).getDistance() }
            ?.takeIf { (_, corner) -> (corner - position).getDistance() <= touchRadius * 1.5f }
            ?.let { return it.first }
        // 锁定比例时只能拖角
        if (ratio != null) return null
        val withinX = position.x in frame.left..frame.right
        val withinY = position.y in frame.top..frame.bottom
        return when {
            withinY && abs(position.x - frame.left) <= touchRadius -> CropHandle.Left
            withinY && abs(position.x - frame.right) <= touchRadius -> CropHandle.Right
            withinX && abs(position.y - frame.top) <= touchRadius -> CropHandle.Top
            withinX && abs(position.y - frame.bottom) <= touchRadius -> CropHandle.Bottom
            else -> null
        }
    }

    /** 能拖的边角各自占的触摸范围。 */
    fun handleBounds(touchRadius: Float): List<Rect> {
        val frame = frame
        val points = buildList {
            add(frame.topLeft)
            add(frame.topRight)
            add(frame.bottomRight)
            add(frame.bottomLeft)
            if (ratio == null) {
                add(Offset(frame.left, frame.center.y))
                add(Offset(frame.right, frame.center.y))
            }
        }
        // 系统每条边最多只让排除 200dp，这里不放大，左右两边各三块加起来也够
        return points.map { Rect(it, touchRadius) }
    }

    /** 把 [handle] 从按下时的框 [start] 拖动了 [delta]。 */
    fun resize(handle: CropHandle, start: Rect, delta: Offset) {
        // 框只能在图片和可用区域的交集里面
        val bounds = imageRect.intersect(area)
        val ratio = ratio
        frame = if (ratio == null) {
            resizeFree(handle, start, delta, bounds)
        } else {
            resizeLocked(handle, start, delta, bounds, ratio)
        }
        modified = true
    }

    private fun resizeFree(handle: CropHandle, start: Rect, delta: Offset, bounds: Rect): Rect {
        // 框本来就比最小尺寸还小时（比如很细长的图），不再强行撑开
        val minWidth = min(minFrameSize, start.width)
        val minHeight = min(minFrameSize, start.height)
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        if (handle.left) left = (start.left + delta.x).safeCoerceIn(bounds.left, right - minWidth)
        if (handle.right) right = (start.right + delta.x).safeCoerceIn(left + minWidth, bounds.right)
        if (handle.top) top = (start.top + delta.y).safeCoerceIn(bounds.top, bottom - minHeight)
        if (handle.bottom) bottom = (start.bottom + delta.y).safeCoerceIn(top + minHeight, bounds.bottom)
        return Rect(left, top, right, bottom)
    }

    // 对角固定不动，按手指拉得更远的那个方向算出宽度，高度跟着比例走
    private fun resizeLocked(handle: CropHandle, start: Rect, delta: Offset, bounds: Rect, ratio: Float): Rect {
        val anchorX = if (handle.left) start.right else start.left
        val anchorY = if (handle.top) start.bottom else start.top
        val pointerX = (if (handle.left) start.left else start.right) + delta.x
        val pointerY = (if (handle.top) start.top else start.bottom) + delta.y
        val wantWidth = if (handle.left) anchorX - pointerX else pointerX - anchorX
        val wantHeight = if (handle.top) anchorY - pointerY else pointerY - anchorY
        val maxWidth = min(
            if (handle.left) anchorX - bounds.left else bounds.right - anchorX,
            (if (handle.top) anchorY - bounds.top else bounds.bottom - anchorY) * ratio,
        )
        val minWidth = min(max(minFrameSize, minFrameSize * ratio), start.width)
        val width = max(wantWidth, wantHeight * ratio).safeCoerceIn(minWidth, maxWidth)
        val height = width / ratio
        val left = if (handle.left) anchorX - width else anchorX
        val top = if (handle.top) anchorY - height else anchorY
        return Rect(left, top, left + width, top + height)
    }

    /** 拖完边角后，框连同图片一起放大并移回区域中间。 */
    suspend fun animateRefit(spec: AnimationSpec<Float>) {
        if (area.isEmpty) return
        val startFrame = frame
        val startOffset = offset
        val startScale = scale
        val k = min(
            min(area.width / startFrame.width, area.height / startFrame.height),
            maxScale / startScale,
        ).coerceAtLeast(1f)
        val targetTopLeft = area.center - Offset(startFrame.width * k / 2, startFrame.height * k / 2)
        if (k == 1f && targetTopLeft == startFrame.topLeft) return
        // 框和图片按同一个相似变换移动，过程中图片始终盖住框
        animate(0f, 1f, animationSpec = spec) { t, _ ->
            val currentK = lerp(1f, k, t)
            val topLeft = lerp(startFrame.topLeft, targetTopLeft, t)
            frame = Rect(topLeft, startFrame.size * currentK)
            offset = topLeft + (startOffset - startFrame.topLeft) * currentK
            scale = startScale * currentK
        }
    }

    /**
     * 按当前的框裁出图片，长边不超过 [maxSize]。比较慢，不要在主线程上调用。
     */
    fun crop(maxSize: Int): Bitmap {
        val image = imageSize
        // 框在转过之后的图片上的位置
        val cropped = Rect(
            left = (frame.left - offset.x) / scale,
            top = (frame.top - offset.y) / scale,
            right = (frame.right - offset.x) / scale,
            bottom = (frame.bottom - offset.y) / scale,
        ).intersect(Rect(Offset.Zero, image))
        val region = rotatedRectToSource(cropped, quarterTurns, source.width.toFloat(), source.height.toFloat())
        val x = region.left.toInt().coerceIn(0, source.width - 1)
        val y = region.top.toInt().coerceIn(0, source.height - 1)
        val width = (region.right.toInt() - x).coerceIn(1, source.width - x)
        val height = (region.bottom.toInt() - y).coerceIn(1, source.height - y)
        val downscale = min(1f, maxSize.toFloat() / max(width, height))
        val matrix = Matrix().apply {
            postRotate(90f * quarterTurns)
            postScale(downscale, downscale)
        }
        return Bitmap.createBitmap(source, x, y, width, height, matrix, true)
    }
}

/**
 * 把顺时针转过 [quarterTurns] 个 90° 的图片上的矩形，换回宽 [width]、高 [height] 的原图上的矩形。
 * 原图 (x, y) 顺时针转 90° 后落在 (height - y, x)。
 */
internal fun rotatedRectToSource(rect: Rect, quarterTurns: Int, width: Float, height: Float): Rect =
    when (quarterTurns) {
        1 -> Rect(rect.top, height - rect.right, rect.bottom, height - rect.left)
        2 -> Rect(width - rect.right, height - rect.bottom, width - rect.left, height - rect.top)
        3 -> Rect(width - rect.bottom, rect.left, width - rect.top, rect.right)
        else -> rect
    }

// 浮点误差或者框本身很小时，下限可能超过上限，这时取中间
private fun Float.safeCoerceIn(min: Float, max: Float): Float =
    if (min > max) (min + max) / 2 else coerceIn(min, max)
