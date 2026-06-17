package me.rerere.rikkahub.data.ai.tools

import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import me.rerere.rikkahub.data.keyboard.KeyboardApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the testable-without-a-live-service surface of the keyboard tools:
 *  - keycode name/int resolution ([resolveKeyCode])
 *  - argument validation envelopes (missing / invalid args early-return before any IPC)
 *  - the approval wiring is the single source of truth in [ToolApprovalDefaults]
 *
 * The success path (a live agent-keyboard bind + AIDL round-trip) needs an instrumented
 * test with the companion app installed — not covered here.
 */
class KeyboardToolsTest {

    // A client built on the ghost NULL_CONTEXT. Validation paths early-return before any
    // client method is reached, so the context is never actually touched.
    private val client = KeyboardApiClient(NULL_CONTEXT)

    private fun exec(tool: Tool, args: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(args))
        (parts.first() as UIMessagePart.Text).text
    }

    // -- resolveKeyCode -----------------------------------------------------------------

    @Test
    fun `resolveKeyCode maps common key names`() {
        assertEquals(KeyEvent.KEYCODE_ENTER, resolveKeyCode("enter"))
        assertEquals(KeyEvent.KEYCODE_ENTER, resolveKeyCode("RETURN"))
        assertEquals(KeyEvent.KEYCODE_TAB, resolveKeyCode("tab"))
        assertEquals(KeyEvent.KEYCODE_DEL, resolveKeyCode("backspace"))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, resolveKeyCode("delete"))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, resolveKeyCode(" up "))
    }

    @Test
    fun `resolveKeyCode accepts raw integer keycodes`() {
        assertEquals(66, resolveKeyCode("66"))
        assertEquals(KeyEvent.KEYCODE_A, resolveKeyCode(KeyEvent.KEYCODE_A.toString()))
    }

    @Test
    fun `resolveKeyCode returns null for unknown or blank input`() {
        assertNull(resolveKeyCode(null))
        assertNull(resolveKeyCode(""))
        assertNull(resolveKeyCode("   "))
        assertNull(resolveKeyCode("not_a_key"))
    }

    // -- argument validation envelopes --------------------------------------------------

    @Test
    fun `keyboard_type without text returns missing_text envelope`() {
        val out = exec(keyboardTypeTool(client), """{}""")
        assertTrue(out.contains("\"error\":\"missing_text\""))
        assertTrue(out.contains("\"recovery\""))
    }

    @Test
    fun `keyboard_press_key with unresolvable key returns invalid_key envelope`() {
        val out = exec(keyboardPressKeyTool(client), """{"key":"flibber"}""")
        assertTrue(out.contains("\"error\":\"invalid_key\""))
        assertTrue(out.contains("\"recovery\""))
    }

    @Test
    fun `keyboard_delete rejects missing and non-positive count`() {
        val missing = exec(keyboardDeleteTool(client), """{}""")
        assertTrue(missing.contains("\"error\":\"missing_count\""))
        val zero = exec(keyboardDeleteTool(client), """{"count":0}""")
        assertTrue(zero.contains("\"error\":\"invalid_count\""))
        val negative = exec(keyboardDeleteTool(client), """{"count":-3}""")
        assertTrue(negative.contains("\"error\":\"invalid_count\""))
    }

    @Test
    fun `keyboard_set_cursor rejects missing and negative pos`() {
        val missing = exec(keyboardSetCursorTool(client), """{}""")
        assertTrue(missing.contains("\"error\":\"missing_pos\""))
        val negative = exec(keyboardSetCursorTool(client), """{"pos":-1}""")
        assertTrue(negative.contains("\"error\":\"invalid_pos\""))
    }

    @Test
    fun `keyboard_select_range rejects missing endpoints and negative offsets`() {
        val missingStart = exec(keyboardSelectRangeTool(client), """{"end":5}""")
        assertTrue(missingStart.contains("\"error\":\"missing_start\""))
        val missingEnd = exec(keyboardSelectRangeTool(client), """{"start":0}""")
        assertTrue(missingEnd.contains("\"error\":\"missing_end\""))
        val negative = exec(keyboardSelectRangeTool(client), """{"start":-1,"end":5}""")
        assertTrue(negative.contains("\"error\":\"invalid_range\""))
    }

    // -- tool surface + approval wiring -------------------------------------------------

    @Test
    fun `write keyboard tools all declare needsApproval`() {
        listOf(
            keyboardTypeTool(client),
            keyboardPressKeyTool(client),
            keyboardDeleteTool(client),
            keyboardClearTool(client),
            keyboardSetCursorTool(client),
            keyboardSelectRangeTool(client),
        ).forEach { tool ->
            assertTrue("${tool.name} should require approval", tool.needsApproval(JsonObject(emptyMap())))
        }
    }

    @Test
    fun `read keyboard tools are not approval gated`() {
        listOf(keyboardReadFieldTool(client), keyboardEditorInfoTool(client)).forEach { tool ->
            assertFalse("${tool.name} is read-only", tool.needsApproval(JsonObject(emptyMap())))
        }
    }

    @Test
    fun `ToolApprovalDefaults gates exactly the six side-effecting keyboard tools`() {
        val gated = setOf(
            "keyboard_type", "keyboard_press_key", "keyboard_delete",
            "keyboard_clear", "keyboard_set_cursor", "keyboard_select_range",
        )
        gated.forEach { assertTrue("$it must be in ALWAYS_ASK", ToolApprovalDefaults.requiresApproval(it)) }
        assertFalse(ToolApprovalDefaults.requiresApproval("keyboard_read_field"))
        assertFalse(ToolApprovalDefaults.requiresApproval("keyboard_editor_info"))
    }
}
