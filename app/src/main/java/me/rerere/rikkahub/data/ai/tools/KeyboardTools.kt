package me.rerere.rikkahub.data.ai.tools

import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.keyboard.KeyboardApiClient

/**
 * LLM tools that drive the active text field through the co-signed agent-keyboard IME.
 *
 * Every tool funnels through [KeyboardApiClient], whose calls are bind- and
 * transaction-time-bounded, so no tool here can hang. Failures come back as a
 * [KeyboardApiClient.Failure] which [failureEnvelope] turns into a `{error, detail,
 * recovery}` object — the recovery text spells out the install / set-as-IME requirement
 * verbatim when that is the cause.
 *
 * Read tools (`keyboard_read_field`, `keyboard_editor_info`) are NOT approval-gated. The
 * side-effecting tools are listed in [ToolApprovalDefaults.ALWAYS_ASK]. None need a
 * HARDLINE arm: typing a string into a focused field is not shell execution and the
 * keyboard itself refuses password / sensitive fields.
 */

// Settle delay between committing typed text and firing Enter in keyboard_type(submit=true).
// Gives the target app a beat to commit the text and re-focus before the key event, which
// is what prevents the type/press_key race that garbles terminal input.
private const val SUBMIT_SETTLE_DELAY_MS = 120L

// keycode aliases the LLM is likely to use, mapped to android.view.KeyEvent constants.
private val KEY_NAME_ALIASES: Map<String, Int> = mapOf(
    "enter" to KeyEvent.KEYCODE_ENTER,
    "return" to KeyEvent.KEYCODE_ENTER,
    "tab" to KeyEvent.KEYCODE_TAB,
    "backspace" to KeyEvent.KEYCODE_DEL,
    "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
    "space" to KeyEvent.KEYCODE_SPACE,
    "escape" to KeyEvent.KEYCODE_ESCAPE,
    "esc" to KeyEvent.KEYCODE_ESCAPE,
    "up" to KeyEvent.KEYCODE_DPAD_UP,
    "down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "left" to KeyEvent.KEYCODE_DPAD_LEFT,
    "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "home" to KeyEvent.KEYCODE_MOVE_HOME,
    "end" to KeyEvent.KEYCODE_MOVE_END,
    "page_up" to KeyEvent.KEYCODE_PAGE_UP,
    "page_down" to KeyEvent.KEYCODE_PAGE_DOWN,
)

/** Resolve a "key" arg that may be a known name OR a raw int keycode. Null if unresolvable. */
internal fun resolveKeyCode(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    raw.trim().toIntOrNull()?.let { return it }
    return KEY_NAME_ALIASES[raw.trim().lowercase()]
}

private fun ok(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("ok", true); build() }.toString()))

private fun errEnvelope(error: String, detail: String, recovery: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", error)
        put("detail", detail)
        put("recovery", recovery)
    }.toString()))

/** Maps a client [KeyboardApiClient.Failure] to the standard error envelope. */
private fun failureEnvelope(failure: KeyboardApiClient.Failure): List<UIMessagePart> = when (failure) {
    KeyboardApiClient.Failure.NOT_INSTALLED -> errEnvelope(
        error = "keyboard_not_installed",
        detail = "The agent-keyboard companion app is not installed on this device.",
        recovery = "Install the agent-keyboard app and set it as the active keyboard " +
            "(Settings > System > Languages & input > On-screen keyboard) before using keyboard tools.",
    )
    KeyboardApiClient.Failure.BIND_REFUSED -> errEnvelope(
        error = "keyboard_bind_refused",
        detail = "Could not bind to the agent-keyboard service or the handshake was rejected.",
        recovery = "Confirm the agent-keyboard app is installed and that it is set as the " +
            "active keyboard. If it is installed but binding still fails, the two apps may " +
            "not be co-signed with the same key.",
    )
    KeyboardApiClient.Failure.OPERATION_FAILED -> errEnvelope(
        error = "keyboard_operation_failed",
        detail = "The keyboard could not perform the action: there may be no focused text " +
            "field, the field may be a password / sensitive field, the request was " +
            "rate-limited, or agent-keyboard is not the active keyboard.",
        recovery = "Make sure a text field is focused and that agent-keyboard is set as the " +
            "active keyboard. Password and sensitive fields are always refused.",
    )
}

private inline fun <T> handle(
    result: KeyboardApiClient.Result<T>,
    onOk: (T) -> List<UIMessagePart>,
): List<UIMessagePart> = when (result) {
    is KeyboardApiClient.Result.Ok -> onOk(result.value)
    is KeyboardApiClient.Result.Err -> failureEnvelope(result.failure)
}

// -- tools ------------------------------------------------------------------------------

fun keyboardTypeTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_type",
    description = "Type text into the currently focused text field on the device via the " +
        "agent-keyboard IME. Inserts at the cursor. Set submit=true to press Enter right " +
        "after typing, as one atomic action - prefer this over a separate keyboard_press_key " +
        "call, which can race the app and garble input (especially in terminals). Fails if " +
        "no field is focused or the field is a password field. In terminal-like apps " +
        "keyboard_read_field returns empty, so take a screenshot first to confirm the prompt " +
        "is ready before typing. Example: keyboard_type(text=\"whoami\", submit=true).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to insert at the cursor.")
                })
                put("submit", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, press Enter after typing as one atomic " +
                        "action (default false). Use for terminals, search boxes, and chat " +
                        "inputs to avoid a type/press_key race.")
                })
            },
            required = listOf("text"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val text = args.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnvelope(
                "missing_text", "text is required",
                "Provide the text to type.",
            )
        val submit = args.jsonObject["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        when (val typed = client.typeText(text)) {
            is KeyboardApiClient.Result.Err -> failureEnvelope(typed.failure)
            is KeyboardApiClient.Result.Ok -> {
                if (!submit) {
                    ok { put("typed", text.length) }
                } else {
                    // Let the app commit the text and re-focus before Enter fires.
                    delay(SUBMIT_SETTLE_DELAY_MS)
                    when (val entered = client.pressKey(KeyEvent.KEYCODE_ENTER)) {
                        is KeyboardApiClient.Result.Err -> failureEnvelope(entered.failure)
                        is KeyboardApiClient.Result.Ok ->
                            ok { put("typed", text.length); put("submitted", true) }
                    }
                }
            }
        }
    },
)

fun keyboardReadFieldTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_read_field",
    description = "Read the full text content of the currently focused text field via the " +
        "agent-keyboard IME. Returns the field text and the selected substring (if any). " +
        "Read-only. Note: terminal emulators (e.g. Termux) and some custom views do not " +
        "expose their buffer to the IME, so this can return empty even when text is " +
        "visible on screen - take a screenshot to read those. Example: keyboard_read_field().",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        when (val text = client.getCurrentText()) {
            is KeyboardApiClient.Result.Ok -> {
                val selected = (client.getSelectedText() as? KeyboardApiClient.Result.Ok)?.value
                ok {
                    put("text", text.value ?: "")
                    put("selected_text", selected ?: "")
                }
            }
            is KeyboardApiClient.Result.Err -> failureEnvelope(text.failure)
        }
    },
)

fun keyboardPressKeyTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_press_key",
    description = "Send a single key press to the focused field via the agent-keyboard IME. " +
        "The 'key' argument accepts a common name (enter, tab, backspace, delete, space, " +
        "escape, up, down, left, right, home, end, page_up, page_down) or a raw Android " +
        "keycode integer. Example: keyboard_press_key(key=\"enter\").",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Key name (enter/tab/backspace/...) or raw keycode integer.")
                })
            },
            required = listOf("key"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val raw = args.jsonObject["key"]?.jsonPrimitive?.contentOrNull
        val keyCode = resolveKeyCode(raw)
            ?: return@Tool errEnvelope(
                "invalid_key", "could not resolve key '$raw' to a keycode",
                "Use a known key name (enter, tab, backspace, ...) or a raw Android keycode integer.",
            )
        handle(client.pressKey(keyCode)) { ok { put("key_code", keyCode) } }
    },
)

fun keyboardDeleteTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_delete",
    description = "Delete a number of characters before the cursor in the focused field via " +
        "the agent-keyboard IME. Example: keyboard_delete(count=3).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many characters before the cursor to delete (>= 1).")
                })
            },
            required = listOf("count"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val count = args.jsonObject["count"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_count", "count is required and must be an integer",
                "Provide a positive integer count.",
            )
        if (count < 1) return@Tool errEnvelope(
            "invalid_count", "count must be >= 1",
            "Provide a positive integer count.",
        )
        handle(client.deleteChars(count)) { ok { put("deleted", count) } }
    },
)

fun keyboardClearTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_clear",
    description = "Clear all text from the currently focused text field via the " +
        "agent-keyboard IME. Example: keyboard_clear().",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = true,
    execute = {
        handle(client.clearField()) { ok { put("cleared", true) } }
    },
)

fun keyboardEditorInfoTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_editor_info",
    description = "Get metadata about the currently focused editor via the agent-keyboard " +
        "IME: owning package, field hint, input type, whether it is a password / multi-line " +
        "field, and the current selection range. Read-only. Use this to decide whether " +
        "typing is safe before calling keyboard_type. Example: keyboard_editor_info().",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        handle(client.getEditorInfo()) { info ->
            if (info == null) {
                failureEnvelope(KeyboardApiClient.Failure.OPERATION_FAILED)
            } else {
                ok {
                    put("package_name", info.packageName ?: "")
                    put("field_hint", info.fieldHint ?: "")
                    put("input_type", info.inputType)
                    put("ime_options", info.imeOptions)
                    put("is_password", info.isPassword)
                    put("is_multi_line", info.isMultiLine)
                    put("selection_start", info.selectionStart)
                    put("selection_end", info.selectionEnd)
                }
            }
        }
    },
)

fun keyboardSetCursorTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_set_cursor",
    description = "Move the cursor in the focused text field to a character position via the " +
        "agent-keyboard IME. Example: keyboard_set_cursor(pos=0) moves to the start.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pos", buildJsonObject {
                    put("type", "integer")
                    put("description", "Character offset to place the cursor at (>= 0).")
                })
            },
            required = listOf("pos"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val pos = args.jsonObject["pos"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_pos", "pos is required and must be an integer",
                "Provide a non-negative integer position.",
            )
        if (pos < 0) return@Tool errEnvelope(
            "invalid_pos", "pos must be >= 0",
            "Provide a non-negative integer position.",
        )
        handle(client.setCursorPosition(pos)) { ok { put("cursor", pos) } }
    },
)

fun keyboardSelectRangeTool(client: KeyboardApiClient): Tool = Tool(
    name = "keyboard_select_range",
    description = "Select a character range in the focused text field via the agent-keyboard " +
        "IME. Example: keyboard_select_range(start=0, end=5) selects the first 5 characters.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("start", buildJsonObject {
                    put("type", "integer")
                    put("description", "Start character offset of the selection (>= 0).")
                })
                put("end", buildJsonObject {
                    put("type", "integer")
                    put("description", "End character offset of the selection (>= 0).")
                })
            },
            required = listOf("start", "end"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val params = args.jsonObject
        val start = params["start"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_start", "start is required and must be an integer",
                "Provide non-negative integer start and end offsets.",
            )
        val end = params["end"]?.jsonPrimitive?.intOrNull
            ?: return@Tool errEnvelope(
                "missing_end", "end is required and must be an integer",
                "Provide non-negative integer start and end offsets.",
            )
        if (start < 0 || end < 0) return@Tool errEnvelope(
            "invalid_range", "start and end must both be >= 0",
            "Provide non-negative integer start and end offsets.",
        )
        handle(client.selectRange(start, end)) {
            ok { put("selection_start", start); put("selection_end", end) }
        }
    },
)
