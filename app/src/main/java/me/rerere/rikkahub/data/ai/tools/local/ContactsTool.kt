package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun phoneTypeName(type: Int): String = when (type) {
    1 -> "home"; 2 -> "mobile"; 3 -> "work"; 7 -> "other"; else -> "unknown"
}

private fun emailTypeName(type: Int): String = when (type) {
    1 -> "home"; 2 -> "work"; 3 -> "other"; else -> "unknown"
}

private fun queryContactSubitems(
    context: Context, contactId: Long, contentUri: Uri,
    valueCol: String, typeCol: String, contactIdCol: String,
    valueKey: String, typeMap: (Int) -> String,
) = buildJsonArray {
    context.contentResolver.query(
        contentUri, arrayOf(valueCol, typeCol),
        "$contactIdCol = ?", arrayOf(contactId.toString()), null
    )?.use { c ->
        val vIdx = c.getColumnIndexOrThrow(valueCol)
        val tIdx = c.getColumnIndexOrThrow(typeCol)
        while (c.moveToNext()) addJsonObject {
            put(valueKey, c.getString(vIdx) ?: "")
            put("type", typeMap(c.getInt(tIdx)))
        }
    }
}

private fun queryContacts(context: Context, uri: Uri): JsonObject = buildJsonObject {
    put("contacts", buildJsonArray {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                addJsonObject {
                    put("id", id)
                    put("display_name", c.getString(nameIdx) ?: "")
                    put("phones", queryContactSubitems(
                        context, id,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        "number", ::phoneTypeName,
                    ))
                    put("emails", queryContactSubitems(
                        context, id,
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                        "address", ::emailTypeName,
                    ))
                }
            }
        }
    })
}

private fun contactsErrorOrRun(context: Context, block: () -> JsonObject): JsonObject {
    if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.READ_CONTACTS))) {
        return buildJsonObject { put("error", "permission READ_CONTACTS not granted") }
    }
    return try {
        block()
    } catch (_: SecurityException) {
        buildJsonObject { put("error", "permission READ_CONTACTS not granted") }
    }
}

fun searchContactsTool(context: Context): Tool = Tool(
    name = "search_contacts",
    description = """
        Search the device's contacts by name or phone substring. Returns matching contacts with
        their phone numbers and email addresses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Name or phone substring to search for")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max contacts to return, default 20, max 100")
                })
            },
            required = listOf("query")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: error("query is required")
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
        val payload = contactsErrorOrRun(context) {
            val uri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon()
                .appendPath(query)
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString())
                .build()
            queryContacts(context, uri)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun listContactsTool(context: Context): Tool = Tool(
    name = "list_contacts",
    description = """
        List the device's contacts. Returns contacts with their phone numbers and email addresses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max contacts to return, default 50, max 500")
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)
        val payload = contactsErrorOrRun(context) {
            val uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString())
                .build()
            queryContacts(context, uri)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
