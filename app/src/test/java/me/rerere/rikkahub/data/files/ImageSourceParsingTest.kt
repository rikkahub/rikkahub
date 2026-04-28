package me.rerere.rikkahub.data.files

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSourceParsingTest {
    @Test
    fun `parse accepts base64 data uri with generic mime`() {
        val source = parseImageSource("data:application/octet-stream;base64,$PNG_BASE64")

        assertTrue(source is ImageSource.DataUri)
        assertEquals(PNG_BASE64, (source as ImageSource.DataUri).base64Data)
    }

    @Test
    fun `parse accepts raw base64 image payload`() {
        val source = parseImageSource(PNG_BASE64)

        assertTrue(source is ImageSource.RawBase64)
        assertEquals(PNG_BASE64, (source as ImageSource.RawBase64).base64Data)
    }

    @Test
    fun `parse accepts content uri`() {
        val source = parseImageSource("content://media/external/images/media/42")

        assertTrue(source is ImageSource.ContentUri)
    }

    @Test
    fun `parse accepts file uri`() {
        val source = parseImageSource("file:///data/user/0/me.rerere.rikkahub/files/images/test.png")

        assertTrue(source is ImageSource.FileUri)
    }

    @Test
    fun `parse accepts local absolute file path`() {
        val tempFile = Files.createTempFile("image-source", ".png").toFile()

        try {
            val source = parseImageSource(tempFile.absolutePath)

            assertTrue(source is ImageSource.LocalPath)
            assertEquals(tempFile.absolutePath, source.raw)
        } finally {
            tempFile.delete()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `parse rejects unsupported image source`() {
        parseImageSource("not-an-image")
    }

    private companion object {
        const val PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+yf9kAAAAASUVORK5CYII="
    }
}
