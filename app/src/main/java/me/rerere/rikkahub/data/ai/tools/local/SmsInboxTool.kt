package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun querySmsInbox(
    context: Context,
    uri: Uri,
    selection: String?,
    selectionArgs: Array<String>?
): Cursor? = context.contentResolver.query(
    uri,
    arrayOf("_id", "address", "body", "date", "read"),
    selection,
    selectionArgs,
    "date DESC"
)

private fun Cursor.toSmsArray() = buildJsonArray {
    val idIdx = getColumnIndexOrThrow("_id")
    val addrIdx = getColumnIndexOrThrow("address")
    val bodyIdx = getColumnIndexOrThrow("body")
    val dateIdx = getColumnIndexOrThrow("date")
    val readIdx = getColumnIndexOrThrow("read")
    while (moveToNext()) {
        addJsonObject {
            put("id", getLong(idIdx))
            put("address", getString(addrIdx) ?: "")
            put("body", getString(bodyIdx) ?: "")
            put("date_ms", getLong(dateIdx))
            put("read", getInt(readIdx) != 0)
        }
    }
}

fun listSmsInboxTool(context: Context): Tool = Tool(
    name = "list_sms_inbox",
    description = """
        List SMS messages from the device's inbox. Most recent first. Optional since-timestamp filter.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max messages to return, default 20, max 200")
                })
                put("since_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional epoch millis lower bound for the message date")
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
        val sinceMs = params["since_ms"]?.jsonPrimitive?.longOrNull

        val payload = if (!PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.READ_SMS)
            )
        ) {
            buildJsonObject { put("error", "permission READ_SMS not granted") }
        } else {
            try {
                val selection = if (sinceMs != null) "date >= ?" else null
                val args = if (sinceMs != null) arrayOf(sinceMs.toString()) else null
                val uri = Telephony.Sms.Inbox.CONTENT_URI.buildUpon()
                    .appendQueryParameter("limit", limit.toString())
                    .build()
                buildJsonObject {
                    put("messages", querySmsInbox(context, uri, selection, args)?.use { it.toSmsArray() }
                        ?: buildJsonArray {})
                }
            } catch (_: SecurityException) {
                buildJsonObject { put("error", "permission READ_SMS not granted") }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun searchSmsTool(context: Context): Tool = Tool(
    name = "search_sms",
    description = """
        Search SMS inbox messages by body substring (case-insensitive LIKE). Most recent first.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Substring to search for in the message body")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max messages to return, default 20, max 200")
                })
            },
            required = listOf("query")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: error("query is required")
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)

        val payload = if (!PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.READ_SMS)
            )
        ) {
            buildJsonObject { put("error", "permission READ_SMS not granted") }
        } else {
            try {
                val uri = Telephony.Sms.Inbox.CONTENT_URI.buildUpon()
                    .appendQueryParameter("limit", limit.toString())
                    .build()
                buildJsonObject {
                    put("messages", querySmsInbox(
                        context, uri, "body LIKE ?", arrayOf("%$query%")
                    )?.use { it.toSmsArray() } ?: buildJsonArray {})
                }
            } catch (_: SecurityException) {
                buildJsonObject { put("error", "permission READ_SMS not granted") }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
