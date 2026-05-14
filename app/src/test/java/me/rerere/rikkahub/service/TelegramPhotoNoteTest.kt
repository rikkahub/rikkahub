package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [buildPhotoNote] — the structured note appended to a Telegram user
 * message so the LLM learns the saved file path of any inbound photo (parity with the
 * existing non-photo attachment note).
 */
class TelegramPhotoNoteTest {

    @Test
    fun `empty path list yields empty note`() {
        assertEquals("", buildPhotoNote(emptyList()))
    }

    @Test
    fun `single photo note includes path, singular noun, and OCR hint`() {
        val note = buildPhotoNote(listOf("/sdcard/Download/telegram_inbox/123/photo_1.jpg"))
        assertTrue(note.startsWith("[User attached 1 photo with this message:"))
        assertTrue(note.contains("- photo → saved to /sdcard/Download/telegram_inbox/123/photo_1.jpg"))
        assertTrue(note.contains("tesseract"))
        assertTrue(note.endsWith("]"))
    }

    @Test
    fun `multiple photos use plural noun and list every path`() {
        val paths = listOf(
            "/sdcard/Download/telegram_inbox/123/photo_1.jpg",
            "/sdcard/Download/telegram_inbox/123/photo_2.jpg",
        )
        val note = buildPhotoNote(paths)
        assertTrue(note.contains("attached 2 photos"))
        paths.forEach { assertTrue("note should list $it", note.contains(it)) }
    }
}
