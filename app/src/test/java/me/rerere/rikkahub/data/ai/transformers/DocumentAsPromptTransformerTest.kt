package me.rerere.rikkahub.data.ai.transformers

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAsPromptTransformerTest {
    @Test
    fun `zip preview should include file list and text contents`() {
        val zipFile = createTempFile(suffix = ".zip").toFile()
        zipFile.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("src/Main.kt"))
                zip.write(
                    """
                    fun main() {
                        println("hello")
                    }
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("README.md"))
                zip.write("# Demo\n\nArchive preview.".toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("assets/logo.png"))
                zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00))
                zip.closeEntry()
            }
        }

        try {
            val preview = DocumentAsPromptTransformer.previewZipArchive(zipFile)

            assertTrue(preview.contains("[ARCHIVE]"))
            assertTrue(preview.contains("src/Main.kt"))
            assertTrue(preview.contains("README.md"))
            assertTrue(preview.contains("assets/logo.png"))
            assertTrue(preview.contains("println(\"hello\")"))
            assertTrue(preview.contains("Archive preview."))
            assertFalse(preview.contains("PNG"))
        } finally {
            zipFile.delete()
        }
    }
}
