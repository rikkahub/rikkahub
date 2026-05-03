package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.provider.CallLog
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

private fun callTypeName(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "incoming"
    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
    CallLog.Calls.MISSED_TYPE -> "missed"
    CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
    CallLog.Calls.REJECTED_TYPE -> "rejected"
    CallLog.Calls.BLOCKED_TYPE -> "blocked"
    else -> "unknown"
}

private fun typeStringToInt(type: String): Int? = when (type) {
    "incoming" -> CallLog.Calls.INCOMING_TYPE
    "outgoing" -> CallLog.Calls.OUTGOING_TYPE
    "missed" -> CallLog.Calls.MISSED_TYPE
    else -> null
}

fun callLogTool(context: Context): Tool = Tool(
    name = "list_call_log",
    description = """
        List recent phone calls from the device's call log. Supports filtering by type
        (incoming, outgoing, missed) and a since-timestamp. Most recent first.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max calls to return, default 20, max 200")
                })
                put("since_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional epoch millis lower bound for the call date")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filter: \"incoming\", \"outgoing\", or \"missed\"")
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
        val sinceMs = params["since_ms"]?.jsonPrimitive?.longOrNull
        val typeStr = params["type"]?.jsonPrimitive?.contentOrNull

        val payload = if (!PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.READ_CALL_LOG)
            )
        ) {
            buildJsonObject { put("error", "permission READ_CALL_LOG not granted") }
        } else {
            val typeInt: Int? = if (typeStr != null) {
                typeStringToInt(typeStr) ?: return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject { put("error", "unknown call type: $typeStr") }.toString()
                    )
                )
            } else null

            try {
                val selectionParts = mutableListOf<String>()
                val selectionArgs = mutableListOf<String>()
                if (typeInt != null) {
                    selectionParts.add("${CallLog.Calls.TYPE} = ?")
                    selectionArgs.add(typeInt.toString())
                }
                if (sinceMs != null) {
                    selectionParts.add("${CallLog.Calls.DATE} >= ?")
                    selectionArgs.add(sinceMs.toString())
                }
                val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
                val args = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()

                val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter("limit", limit.toString())
                    .build()

                buildJsonObject {
                    put("calls", buildJsonArray {
                        context.contentResolver.query(
                            uri,
                            arrayOf(
                                CallLog.Calls._ID,
                                CallLog.Calls.NUMBER,
                                CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.TYPE,
                                CallLog.Calls.DATE,
                                CallLog.Calls.DURATION,
                            ),
                            selection, args,
                            "${CallLog.Calls.DATE} DESC"
                        )?.use { c ->
                            val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                            val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                            val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                            val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                            val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                            val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                            while (c.moveToNext()) {
                                addJsonObject {
                                    put("id", c.getLong(idIdx))
                                    put("number", c.getString(numIdx) ?: "")
                                    val name = c.getString(nameIdx)
                                    if (!name.isNullOrEmpty()) put("name", name)
                                    put("type", callTypeName(c.getInt(typeIdx)))
                                    put("date_ms", c.getLong(dateIdx))
                                    put("duration_s", c.getLong(durIdx))
                                }
                            }
                        }
                    })
                }
            } catch (_: SecurityException) {
                buildJsonObject { put("error", "permission READ_CALL_LOG not granted") }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
