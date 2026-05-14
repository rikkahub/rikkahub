package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private fun nfcErr(msg: String) =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", msg) }.toString()))

/** Validates the NFC record-list shape + timeout. Shared with the unit test. Returns null when ok. */
internal fun validateNfcTimeout(timeoutSeconds: Int): String? = when {
    timeoutSeconds < 5 || timeoutSeconds > 120 -> "timeout_seconds must be between 5 and 120"
    else -> null
}

@OptIn(ExperimentalUuidApi::class)
private fun isHeadless(invocationContext: ToolInvocationContext): Boolean {
    if (invocationContext.isHeadless) return true
    val convId = invocationContext.callerConversationId ?: return false
    return runCatching { HeadlessConversations.isHeadless(Uuid.parse(convId)) }.getOrDefault(false)
}

/** NFC adapter availability check. Returns an error string when NFC is unusable, null when ok. */
private fun nfcAvailability(context: Context): String? {
    val adapter = NfcAdapter.getDefaultAdapter(context) ?: return "feature unavailable"
    if (!adapter.isEnabled) return "NFC is turned off in system settings"
    return null
}

private const val NFC_BUFFER_MARGIN_MS = 5_000L

/**
 * `nfc_read_tag` — open a foreground NFC reader-mode session via [ToolHostActivity] and
 * return the NDEF records of the first tag tapped. Refuses in headless mode (no Activity
 * the user could tap a tag against).
 */
fun nfcReadTagTool(
    context: Context,
    buffer: NfcResultBuffer,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "nfc_read_tag",
    description = """
        Read NDEF records from an NFC tag. Opens a foreground reader session; the user
        taps a tag against the phone. Returns {records: [{kind, value, ...}], tag_id_hex}.
        Record kinds: text, uri, raw (base64 payload + tnf + type_b64).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer"); put("minimum", 5); put("maximum", 120)
                })
            },
        )
    },
    execute = { input ->
        if (isHeadless(invocationContext)) {
            return@Tool nfcErr("feature unavailable in headless mode")
        }
        val timeoutSeconds = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30
        validateNfcTimeout(timeoutSeconds)?.let { return@Tool nfcErr(it) }
        nfcAvailability(context)?.let { return@Tool nfcErr(it) }

        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)
        val intent = Intent(context, ToolHostActivity::class.java).apply {
            putExtra(ToolHostActivity.EXTRA_MODE, ToolHostActivity.MODE_NFC_READ)
            putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(ToolHostActivity.EXTRA_NFC_TIMEOUT, timeoutSeconds)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        val result = withTimeoutOrNull(timeoutSeconds * 1000L + NFC_BUFFER_MARGIN_MS) {
            deferred.await()
        }
        when (result) {
            is NfcResult.ReadOk -> listOf(UIMessagePart.Text(buildJsonObject {
                put("records", Json.parseToJsonElement(result.recordsJson))
                put("tag_id_hex", result.tagIdHex)
            }.toString()))
            is NfcResult.Timeout -> nfcErr("no tag tapped before timeout")
            is NfcResult.Error -> nfcErr(result.code)
            is NfcResult.WriteOk, null -> {
                buffer.complete(requestId, NfcResult.Timeout)
                nfcErr("no tag tapped before timeout")
            }
        }
    },
)

/**
 * `nfc_write_tag` — open a foreground NFC reader session and write the supplied NDEF
 * records to the first writable tag tapped. Refuses in headless mode.
 */
fun nfcWriteTagTool(
    context: Context,
    buffer: NfcResultBuffer,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "nfc_write_tag",
    description = """
        Write NDEF records to an NFC tag. Opens a foreground reader session; the user taps
        a writable tag. records is an array of {kind, value, ...} (kinds: text, uri, raw).
        Returns {success, tag_id_hex}. Refuses read-only tags.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("records", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("kind", buildJsonObject { put("type", "string") })
                            put("value", buildJsonObject { put("type", "string") })
                            put("tnf", buildJsonObject { put("type", "integer") })
                            put("type_b64", buildJsonObject { put("type", "string") })
                        })
                    })
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer"); put("minimum", 5); put("maximum", 120)
                })
            },
            required = listOf("records"),
        )
    },
    execute = { input ->
        if (isHeadless(invocationContext)) {
            return@Tool nfcErr("feature unavailable in headless mode")
        }
        val obj = input.jsonObject
        val records: JsonArray = obj["records"]?.jsonArray
            ?: return@Tool nfcErr("records is required")
        if (records.isEmpty()) return@Tool nfcErr("records must not be empty")
        // Structural pre-validation so a bad record fails fast before opening the Activity.
        runCatching { NfcNdefCodec.encode(records) }
            .onFailure { return@Tool nfcErr("invalid records: ${it.message}") }
        val timeoutSeconds = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30
        validateNfcTimeout(timeoutSeconds)?.let { return@Tool nfcErr(it) }
        nfcAvailability(context)?.let { return@Tool nfcErr(it) }

        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)
        val intent = Intent(context, ToolHostActivity::class.java).apply {
            putExtra(ToolHostActivity.EXTRA_MODE, ToolHostActivity.MODE_NFC_WRITE)
            putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(ToolHostActivity.EXTRA_NFC_TIMEOUT, timeoutSeconds)
            putExtra(ToolHostActivity.EXTRA_NFC_RECORDS, records.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        val result = withTimeoutOrNull(timeoutSeconds * 1000L + NFC_BUFFER_MARGIN_MS) {
            deferred.await()
        }
        when (result) {
            is NfcResult.WriteOk -> listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("tag_id_hex", result.tagIdHex)
            }.toString()))
            is NfcResult.Timeout -> nfcErr("no tag tapped before timeout")
            is NfcResult.Error -> nfcErr(result.code)
            is NfcResult.ReadOk, null -> {
                buffer.complete(requestId, NfcResult.Timeout)
                nfcErr("no tag tapped before timeout")
            }
        }
    },
)
