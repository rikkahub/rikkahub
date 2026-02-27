package me.rerere.ai.util

import org.junit.Assert.assertEquals
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
