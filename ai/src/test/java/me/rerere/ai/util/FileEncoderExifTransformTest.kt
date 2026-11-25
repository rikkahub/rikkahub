package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEncoderExifTransformTest {

    @Test
    fun `all supported exif orientations should map to expected transform`() {
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(ORIENTATION_NORMAL))
        assertEquals(ExifTransformType.FLIP_HORIZONTAL, mapExifOrientationToTransform(ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(ExifTransformType.ROTATE_180, mapExifOrientationToTransform(ORIENTATION_ROTATE_180))
        assertEquals(ExifTransformType.FLIP_VERTICAL, mapExifOrientationToTransform(ORIENTATION_FLIP_VERTICAL))
        assertEquals(ExifTransformType.TRANSPOSE, mapExifOrientationToTransform(ORIENTATION_TRANSPOSE))
        assertEquals(ExifTransformType.ROTATE_90, mapExifOrientationToTransform(ORIENTATION_ROTATE_90))
        assertEquals(ExifTransformType.TRANSVERSE, mapExifOrientationToTransform(ORIENTATION_TRANSVERSE))
        assertEquals(ExifTransformType.ROTATE_270, mapExifOrientationToTransform(ORIENTATION_ROTATE_270))
    }

    @Test
    fun `undefined or unknown orientation should map to none`() {
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(ORIENTATION_UNDEFINED))
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(999))
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(-1))
    }

    @Test
    fun `long screenshots should keep original resolution when under pixel budget`() {
        val target = calculateImageTargetSize(
            width = 1272,
            height = 2800,
            maxDimension = 10_000,
            maxPixels = 4_000_000L
        )

        assertEquals(ImageSize(1272, 2800), target)
    }

    @Test
    fun `large photos should be scaled down to pixel budget`() {
        val target = calculateImageTargetSize(
            width = 4000,
            height = 3000,
            maxDimension = 10_000,
            maxPixels = 4_000_000L
        )

        assertTrue(target.width.toLong() * target.height <= 4_000_000L)
        // 只按需缩小，不会因为 2 的幂次采样缩过头
        assertTrue(target.width.toLong() * target.height > 3_900_000L)
        assertEquals(4.0 / 3.0, target.width.toDouble() / target.height, 0.01)
    }

    @Test
    fun `extremely long images should still be scaled down by max dimension`() {
        val target = calculateImageTargetSize(
            width = 100,
            height = 20_000,
            maxDimension = 10_000,
            maxPixels = 4_000_000L
        )

        assertEquals(ImageSize(50, 10_000), target)
    }

    @Test
    fun `sample size should not go below target size`() {
        // 4000x3000 缩到约 2309x1732：采样 2 会变成 2000x1500，比目标还小，只能采样 1
        assertEquals(1, calculateImageInSampleSize(4000, 3000, targetWidth = 2309, targetHeight = 1732))
        // 8000x6000 采样 2 后 4000x3000 仍大于目标，采样 4 后 2000x1500 小于目标
        assertEquals(2, calculateImageInSampleSize(8000, 6000, targetWidth = 2309, targetHeight = 1732))
        assertEquals(1, calculateImageInSampleSize(1272, 2800, targetWidth = 1272, targetHeight = 2800))
    }

    companion object {
        private const val ORIENTATION_UNDEFINED = 0
        private const val ORIENTATION_NORMAL = 1
        private const val ORIENTATION_FLIP_HORIZONTAL = 2
        private const val ORIENTATION_ROTATE_180 = 3
        private const val ORIENTATION_FLIP_VERTICAL = 4
        private const val ORIENTATION_TRANSPOSE = 5
        private const val ORIENTATION_ROTATE_90 = 6
        private const val ORIENTATION_TRANSVERSE = 7
        private const val ORIENTATION_ROTATE_270 = 8
    }
}
