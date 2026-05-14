package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [buildShowImageEnvelope] — the text envelope `show_image` hands the
 * LLM. The bug this pins: a text-only model handed `success:true` + width/height with no
 * caveat treats it as "I looked at it" and confabulates an image description. The envelope
 * must tell a non-vision model plainly that it cannot see the image.
 */
class ShowImageEnvelopeTest {

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `vision model envelope carries metadata and no cannot-see caveat`() {
        val env = parse(
            buildShowImageEnvelope(
                path = "/sdcard/Download/a.jpg",
                mime = "image/jpeg",
                sizeBytes = 58141,
                width = 1144,
                height = 1280,
                modelCanSeeImages = true,
            )
        )
        assertEquals(true, env["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("/sdcard/Download/a.jpg", env["path"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", env["mime"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1144, env["width"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(1280, env["height"]?.jsonPrimitive?.contentOrNull?.toInt())
        // Vision models must NOT be told they can't see — that would make them OCR
        // needlessly and distrust an image they can actually read.
        assertNull(env["visible_to_you"])
        assertNull(env["note"])
    }

    @Test
    fun `text-only model envelope says it cannot see and points at OCR`() {
        val env = parse(
            buildShowImageEnvelope(
                path = "/sdcard/Download/telegram_inbox/123/photo_1.jpg",
                mime = "image/jpeg",
                sizeBytes = 58141,
                width = 1144,
                height = 1280,
                modelCanSeeImages = false,
            )
        )
        // Metadata still present — useful for OCR planning.
        assertEquals(true, env["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1144, env["width"]?.jsonPrimitive?.contentOrNull?.toInt())
        // The caveat that kills the confabulation.
        assertEquals(false, env["visible_to_you"]?.jsonPrimitive?.booleanOrNull)
        val note = env["note"]?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue("note should say the model cannot see it", note.contains("cannot see"))
        assertTrue("note should forbid guessing", note.contains("Do not describe or guess"))
        assertTrue("note should point at OCR", note.contains("tesseract"))
    }
}
