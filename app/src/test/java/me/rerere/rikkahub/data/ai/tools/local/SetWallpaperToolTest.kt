package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 25 — `set_wallpaper` validation. Exercises [validateWallpaperArgs] plus the tool's
 * early-return validation path.
 */
class SetWallpaperToolTest {

    @Test fun `validate rejects blank file uri`() {
        assertEquals("file_uri is required", validateWallpaperArgs(null, "both"))
        assertEquals("file_uri is required", validateWallpaperArgs("", "both"))
    }

    @Test fun `validate rejects non-file uri`() {
        assertEquals(
            "file_uri must start with file://",
            validateWallpaperArgs("content://media/123", "both"),
        )
        assertEquals(
            "file_uri must start with file://",
            validateWallpaperArgs("/sdcard/pic.jpg", "both"),
        )
    }

    @Test fun `validate rejects unknown target`() {
        assertEquals(
            "target must be one of home, lock, both",
            validateWallpaperArgs("file:///sdcard/pic.jpg", "screensaver"),
        )
    }

    @Test fun `validate accepts each valid target`() {
        assertNull(validateWallpaperArgs("file:///sdcard/pic.jpg", "home"))
        assertNull(validateWallpaperArgs("file:///sdcard/pic.jpg", "lock"))
        assertNull(validateWallpaperArgs("file:///sdcard/pic.jpg", "both"))
    }

    @Test fun `tool early-returns validation error for bad uri`() {
        val tool = setWallpaperTool(NULL_CONTEXT)
        val out = execTool(tool, """{"file_uri":"/sdcard/pic.jpg"}""")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("file_uri must start with file://", obj["error"]?.jsonPrimitive?.content)
    }

    @Test fun `tool early-returns validation error for bad target`() {
        val tool = setWallpaperTool(NULL_CONTEXT)
        val out = execTool(tool, """{"file_uri":"file:///sdcard/pic.jpg","target":"xyz"}""")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("target must be one of home, lock, both", obj["error"]?.jsonPrimitive?.content)
    }
}
