package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the file-cleanup decision that gates [ChatService] file deletion (issue #107). Streaming
 * chunk updates only ever append parts, so [removedFileUris] must never report a removal for a
 * superset; explicit edit/delete/fork mutations that drop a reference must still report it.
 */
class ChatServiceFileCleanupTest {

    @Test
    fun `streaming append superset removes nothing`() {
        val oldFiles = listOf("file:///chat/a.png", "file:///chat/b.png")
        val newFiles = oldFiles + "file:///chat/c.png"

        assertTrue(removedFileUris(oldFiles, newFiles).isEmpty())
    }

    @Test
    fun `removed file is reported exactly`() {
        val oldFiles = listOf("file:///chat/a.png", "file:///chat/b.png")
        val newFiles = listOf("file:///chat/a.png")

        assertEquals(listOf("file:///chat/b.png"), removedFileUris(oldFiles, newFiles))
    }

    @Test
    fun `identical lists remove nothing`() {
        val files = listOf("file:///chat/a.png", "file:///chat/b.png")

        assertTrue(removedFileUris(files, files).isEmpty())
    }
}
