package me.rerere.ui.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCropStateTest {
    private val width = 400f
    private val height = 300f

    // 和画布上画图时的变换一致：绕中心顺时针转，转完左上角回到原点
    private fun rotate(point: Offset, quarterTurns: Int): Offset = when (quarterTurns) {
        1 -> Offset(height - point.y, point.x)
        2 -> Offset(width - point.x, height - point.y)
        3 -> Offset(point.y, width - point.x)
        else -> point
    }

    private fun boundsOf(points: List<Offset>) = Rect(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y },
    )

    @Test
    fun `maps rotated rect back to the same pixels of the source`() {
        val rotatedRect = Rect(10f, 20f, 110f, 70f)
        for (quarterTurns in 0..3) {
            val source = rotatedRectToSource(rotatedRect, quarterTurns, width, height)
            val corners = listOf(source.topLeft, source.topRight, source.bottomRight, source.bottomLeft)
            assertEquals(
                "quarterTurns = $quarterTurns",
                rotatedRect,
                boundsOf(corners.map { rotate(it, quarterTurns) }),
            )
        }
    }

    @Test
    fun `whole rotated image maps to whole source`() {
        for (quarterTurns in 0..3) {
            val rotated = if (quarterTurns % 2 == 0) Rect(0f, 0f, width, height) else Rect(0f, 0f, height, width)
            assertEquals(
                "quarterTurns = $quarterTurns",
                Rect(0f, 0f, width, height),
                rotatedRectToSource(rotated, quarterTurns, width, height),
            )
        }
    }
}
