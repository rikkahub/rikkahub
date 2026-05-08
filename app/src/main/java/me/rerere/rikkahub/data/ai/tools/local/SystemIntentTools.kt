package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * Phase 18 — first-class native-intent tools matching Google AI Edge Gallery's Mobile
 * Actions surface. Each tool dispatches a system intent the OS handles natively (calendar
 * picker, contact picker, email composer, etc.) so the user reviews + finalises in the
 * destination app rather than the LLM committing the action silently.
 *
 * Why first-class instead of using `share` / `open_url`:
 *  - The LLM can pass typed args (subject + body + recipient) instead of pre-formatting
 *    a URL.
 *  - The chat surface can render an "action chip" (Phase 18C) showing what just fired,
 *    matching Gallery's UX.
 *  - Approval prompts can be human-readable ("Draft an email to bob@ex.com — Subject:
 *    Lunch?") instead of raw JSON.
 *
 * Each tool returns an `intent_fired` envelope the chat renderer picks up. The
 * destination app may or may not handle the intent — we don't follow up; the user takes
 * over once the intent fires.
 */

fun createCalendarEventTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "create_calendar_event",
    description = """
        Open the system Calendar app pre-filled with a new event the user can review and save.
        Useful for "schedule a meeting", "add an event for", "remind me on date X".
        The user finalises in their default Calendar app — no event is saved without their
        explicit save action.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Title of the event.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional event description / notes.")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional location for the event.")
                })
                put("start_time_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional start time in unix ms. If omitted, the calendar app picks the user's current time.")
                })
                put("end_time_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional end time in unix ms.")
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True for an all-day event. Defaults to false.")
                })
            },
            required = listOf("title"),
        )
    },
    needsApproval = true,
    execute = { args ->
        wakeScreenIfNeeded(context)
        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_title", "title is required")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            params["description"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(CalendarContract.Events.DESCRIPTION, it)
            }
            params["location"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(CalendarContract.Events.EVENT_LOCATION, it)
            }
            params["start_time_unix_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
            params["end_time_unix_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
            }
            params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = fireIntent(context, intent, action = "create_calendar_event", summary = "Calendar event: $title")
        streamer.streamIfHeadless(invocationContext, "CreateCalendarEvent: $title")
        result
    },
)

fun createContactTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "create_contact",
    description = """
        Open the system Contacts app pre-filled with a new contact the user can review and save.
        Useful for "save this person's number", "add Bob to my contacts".
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("first_name", buildJsonObject {
                    put("type", "string")
                    put("description", "First / given name.")
                })
                put("last_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Last / family name.")
                })
                put("phone_number", buildJsonObject {
                    put("type", "string")
                    put("description", "Phone number.")
                })
                put("email", buildJsonObject {
                    put("type", "string")
                    put("description", "Email address.")
                })
                put("organization", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional company / organization name.")
                })
            },
        )
    },
    needsApproval = true,
    execute = { args ->
        wakeScreenIfNeeded(context)
        val params = args.jsonObject
        val firstName = params["first_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lastName = params["last_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val displayName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (displayName.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, displayName)
            params["phone_number"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(ContactsContract.Intents.Insert.PHONE, it)
            }
            params["email"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(ContactsContract.Intents.Insert.EMAIL, it)
            }
            params["organization"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(ContactsContract.Intents.Insert.COMPANY, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = fireIntent(
            context, intent, action = "create_contact",
            summary = "Contact draft: ${displayName.ifBlank { "(unnamed)" }}",
        )
        streamer.streamIfHeadless(invocationContext, "CreateContact: ${displayName.ifBlank { "(unnamed)" }}")
        result
    },
)

fun sendEmailIntentTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "send_email_intent",
    description = """
        Open the user's default email app composer pre-filled with recipient, subject, and body.
        The user reviews and presses Send — no email is sent silently. Use this for "email Bob
        about ..." type asks where the user wants to control the actual send action.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("to", buildJsonObject {
                    put("type", "string")
                    put("description", "Email address of the recipient.")
                })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("description", "Subject line.")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Body / message text.")
                })
            },
            required = listOf("to"),
        )
    },
    needsApproval = true,
    execute = { args ->
        wakeScreenIfNeeded(context)
        val params = args.jsonObject
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_to", "to is required")
        val subject = params["subject"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body = params["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = fireIntent(
            context, intent, action = "send_email_intent",
            summary = "Email draft to $to" + if (subject.isNotEmpty()) " — $subject" else "",
        )
        streamer.streamIfHeadless(invocationContext, "SendEmailIntent to $to")
        result
    },
)

fun sendSmsIntentTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "send_sms_intent",
    description = """
        Open the system SMS composer pre-filled with recipient and message body. The user
        reviews and presses Send — no SMS is sent silently.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("phone_number", buildJsonObject {
                    put("type", "string")
                    put("description", "Recipient's phone number.")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Message text.")
                })
            },
            required = listOf("phone_number"),
        )
    },
    needsApproval = true,
    execute = { args ->
        wakeScreenIfNeeded(context)
        val params = args.jsonObject
        val phone = params["phone_number"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_phone_number", "phone_number is required")
        val body = params["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "smsto:$phone".toUri()
            if (body.isNotEmpty()) putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = fireIntent(
            context, intent, action = "send_sms_intent",
            summary = "SMS draft to $phone" + if (body.isNotEmpty()) ": ${body.take(40)}" else "",
        )
        streamer.streamIfHeadless(invocationContext, "SendSmsIntent to $phone")
        result
    },
)

fun openWifiSettingsTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "open_wifi_settings",
    description = """
        Open the system WiFi Settings page so the user can connect to a network, forget one,
        or toggle WiFi. Use when the user asks "open my WiFi settings" or you've detected
        WiFi is off and the user wants to fix it.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = true,
    execute = { _ ->
        wakeScreenIfNeeded(context)
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = fireIntent(context, intent, action = "open_wifi_settings", summary = "Opened WiFi Settings")
        streamer.streamIfHeadless(invocationContext, "OpenWifiSettings")
        result
    },
)

fun showLocationOnMapTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "show_location_on_map",
    description = """
        Open the user's default maps app showing the given place / address / coordinates.
        Useful for "where is X?", "show me the way to X", "find Y on the map".
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Place name, address, or 'lat,lng' coordinate pair.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = true,
    execute = { args ->
        wakeScreenIfNeeded(context)
        val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_query", "query is required")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "geo:0,0?q=${Uri.encode(query)}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = fireIntent(
            context, intent, action = "show_location_on_map",
            summary = "Map: $query",
        )
        streamer.streamIfHeadless(invocationContext, "ShowLocationOnMap: ${query.take(50)}")
        result
    },
)

// -- shared helpers -------------------------------------------------------------------

/**
 * Dispatch the intent and surface the result in a structured envelope the chat-side
 * renderer (Phase 18C) detects and shows as an action chip. The envelope shape:
 *
 * ```
 * { "intent_fired": true,
 *   "action": "create_calendar_event",
 *   "summary": "Calendar event: Lunch with Bob",
 *   "ok": true | false,
 *   "error"?: "no_handler" }
 * ```
 *
 * `ok=false` happens when the OS has no app capable of handling the intent (no calendar
 * app installed, no email client, etc.) — the chat surface shows the failure card.
 */
private fun fireIntent(
    context: Context,
    intent: Intent,
    action: String,
    summary: String,
): List<UIMessagePart> {
    return try {
        if (intent.resolveActivity(context.packageManager) == null) {
            return listOf(UIMessagePart.Text(buildJsonObject {
                put("intent_fired", false)
                put("action", action)
                put("ok", false)
                put("error", "no_handler")
                put("summary", summary)
                put("detail", "no installed app can handle this intent")
            }.toString()))
        }
        context.startActivity(intent)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("intent_fired", true)
            put("action", action)
            put("ok", true)
            put("summary", summary)
        }.toString()))
    } catch (t: Throwable) {
        listOf(UIMessagePart.Text(buildJsonObject {
            put("intent_fired", false)
            put("action", action)
            put("ok", false)
            put("error", t::class.simpleName ?: "exception")
            put("summary", summary)
            put("detail", t.message.orEmpty())
        }.toString()))
    }
}

private fun err(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }.toString()))
