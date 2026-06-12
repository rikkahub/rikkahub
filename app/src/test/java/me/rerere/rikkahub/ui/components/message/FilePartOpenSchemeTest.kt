package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the message-part tap handlers (Video/Audio/Document).
 *
 * Bug: every handler called part.url.toUri().toFile(), which throws
 * IllegalArgumentException for any non-file:// url. Remote (http/https) and
 * content:// urls are legal values for these parts, so a tap crashed the app.
 * The scheme classifier below is what routes those urls away from the
 * FileProvider/ACTION_VIEW path and into the plain openUrl fallback.
 */
class FilePartOpenSchemeTest {

    @Test
    fun `file uris take the FileProvider path`() {
        assertTrue(isFileSchemeUrl("file:///data/user/0/app/files/upload/doc.pdf"))
        assertTrue(isFileSchemeUrl("FILE:///sdcard/Music/clip.mp3"))
    }

    @Test
    fun `remote urls do not reach Uri_toFile`() {
        assertFalse(isFileSchemeUrl("https://example.com/video.mp4"))
        assertFalse(isFileSchemeUrl("http://example.com/audio.mp3"))
    }

    @Test
    fun `content and data uris do not reach Uri_toFile`() {
        assertFalse(isFileSchemeUrl("content://media/external/video/media/42"))
        assertFalse(isFileSchemeUrl("data:application/pdf;base64,AAAA"))
    }

    @Test
    fun `bare paths without a scheme do not reach Uri_toFile`() {
        assertFalse(isFileSchemeUrl("/storage/emulated/0/Download/doc.pdf"))
        assertFalse(isFileSchemeUrl(""))
    }
}
