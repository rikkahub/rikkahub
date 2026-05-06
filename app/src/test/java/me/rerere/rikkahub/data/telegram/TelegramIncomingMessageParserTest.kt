package me.rerere.rikkahub.data.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramIncomingMessageParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun update(messageJson: String): kotlinx.serialization.json.JsonObject {
        return json.parseToJsonElement("""
            {
              "update_id": 100,
              "message": $messageJson
            }
        """.trimIndent()).jsonObject
    }

    // ---- plain text ----

    @Test
    fun `plain text message — no attachments`() {
        val u = update("""
            {
              "message_id": 1,
              "chat": {"id": 42},
              "from": {"id": 99},
              "text": "Hello bot"
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals("Hello bot", m!!.text)
        assertEquals(emptyList<String>(), m.photoFileIds)
        assertEquals(emptyList<TelegramAttachment>(), m.attachments)
    }

    // ---- photo only (existing behaviour preserved) ----

    @Test
    fun `photo only — photoFileIds populated, attachments empty`() {
        val u = update("""
            {
              "message_id": 2,
              "chat": {"id": 42},
              "from": {"id": 99},
              "photo": [
                {"file_id": "small_id", "width": 90, "height": 90, "file_size": 1000},
                {"file_id": "large_id", "width": 1280, "height": 720, "file_size": 80000}
              ]
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals(listOf("large_id"), m!!.photoFileIds)
        assertTrue(m.attachments.isEmpty())
    }

    // ---- document with caption ----

    @Test
    fun `document with caption — text and 1 DOCUMENT attachment`() {
        val u = update("""
            {
              "message_id": 3,
              "chat": {"id": 42},
              "from": {"id": 99},
              "caption": "save it",
              "document": {
                "file_id": "doc_file_id",
                "file_name": "Invoice March 2026.pdf",
                "mime_type": "application/pdf",
                "file_size": 1258291
              }
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals("save it", m!!.text)
        assertEquals(1, m.attachments.size)
        val att = m.attachments[0]
        assertEquals(AttachmentKind.DOCUMENT, att.kind)
        assertEquals("doc_file_id", att.fileId)
        assertEquals("Invoice March 2026.pdf", att.originalFileName)
        assertEquals("application/pdf", att.mimeType)
        assertEquals(1258291L, att.sizeBytes)
        assertNull(att.durationSec)
    }

    // ---- audio with no caption ----

    @Test
    fun `audio with no caption — empty text, 1 AUDIO attachment`() {
        val u = update("""
            {
              "message_id": 4,
              "chat": {"id": 42},
              "from": {"id": 99},
              "audio": {
                "file_id": "audio_file_id",
                "file_name": "recording.mp3",
                "mime_type": "audio/mpeg",
                "file_size": 307200,
                "duration": 30
              }
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals("", m!!.text)
        assertEquals(1, m.attachments.size)
        val att = m.attachments[0]
        assertEquals(AttachmentKind.AUDIO, att.kind)
        assertEquals("audio_file_id", att.fileId)
        assertEquals("recording.mp3", att.originalFileName)
        assertEquals(30, att.durationSec)
    }

    // ---- video ----

    @Test
    fun `video — VIDEO attachment with duration`() {
        val u = update("""
            {
              "message_id": 5,
              "chat": {"id": 42},
              "from": {"id": 99},
              "video": {
                "file_id": "vid_file_id",
                "file_name": "clip.mp4",
                "mime_type": "video/mp4",
                "file_size": 10485760,
                "duration": 120
              }
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals(1, m!!.attachments.size)
        val att = m.attachments[0]
        assertEquals(AttachmentKind.VIDEO, att.kind)
        assertEquals("vid_file_id", att.fileId)
        assertEquals("clip.mp4", att.originalFileName)
        assertEquals(120, att.durationSec)
    }

    // ---- voice ----

    @Test
    fun `voice — VOICE attachment with duration, no filename`() {
        val u = update("""
            {
              "message_id": 6,
              "chat": {"id": 42},
              "from": {"id": 99},
              "voice": {
                "file_id": "voice_file_id",
                "mime_type": "audio/ogg",
                "file_size": 40960,
                "duration": 15
              }
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals(1, m!!.attachments.size)
        val att = m.attachments[0]
        assertEquals(AttachmentKind.VOICE, att.kind)
        assertNull(att.originalFileName)
        assertEquals("audio/ogg", att.mimeType)
        assertEquals(15, att.durationSec)
    }

    // ---- video_note ----

    @Test
    fun `video note — VIDEO_NOTE attachment, no filename, mime video-mp4`() {
        val u = update("""
            {
              "message_id": 7,
              "chat": {"id": 42},
              "from": {"id": 99},
              "video_note": {
                "file_id": "vnote_file_id",
                "length": 240,
                "file_size": 204800,
                "duration": 10
              }
            }
        """)
        val m = parseIncoming(u)
        assertNotNull(m)
        assertEquals(1, m!!.attachments.size)
        val att = m.attachments[0]
        assertEquals(AttachmentKind.VIDEO_NOTE, att.kind)
        assertNull(att.originalFileName)
        assertEquals("video/mp4", att.mimeType)
        assertEquals(10, att.durationSec)
    }

    // ---- out-of-scope types are dropped when no actionable content ----

    @Test
    fun `sticker only — dropped (nothing actionable)`() {
        val u = update("""
            {
              "message_id": 8,
              "chat": {"id": 42},
              "from": {"id": 99},
              "sticker": {
                "file_id": "sticker_id",
                "width": 512,
                "height": 512
              }
            }
        """)
        // Sticker is not in scope; the message has no text, photo, or supported attachment.
        assertNull(parseIncoming(u))
    }

    @Test
    fun `animation only — dropped`() {
        val u = update("""
            {
              "message_id": 9,
              "chat": {"id": 42},
              "from": {"id": 99},
              "animation": {
                "file_id": "anim_id",
                "width": 400,
                "height": 300,
                "duration": 2
              }
            }
        """)
        assertNull(parseIncoming(u))
    }

    @Test
    fun `location — dropped`() {
        val u = update("""
            {
              "message_id": 10,
              "chat": {"id": 42},
              "from": {"id": 99},
              "location": {"longitude": 13.4050, "latitude": 52.5200}
            }
        """)
        assertNull(parseIncoming(u))
    }
}
