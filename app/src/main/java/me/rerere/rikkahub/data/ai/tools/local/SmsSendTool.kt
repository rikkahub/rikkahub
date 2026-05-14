package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.telephony.SmsManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val SMS_RECIPIENT_REGEX = Regex("^[+]?[0-9 \\-()]+$")
private const val SMS_MAX_BODY = 4096

/** Structural validation shared with the unit test. Returns null when ok. */
internal fun validateSmsArgs(recipient: String?, body: String?): String? = when {
    recipient.isNullOrBlank() -> "recipient is required"
    !SMS_RECIPIENT_REGEX.matches(recipient) -> "recipient must be a phone number"
    body.isNullOrEmpty() -> "body is required"
    body.length > SMS_MAX_BODY -> "body must be <= $SMS_MAX_BODY characters"
    else -> null
}

private fun smsErr(msg: String) =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", msg) }.toString()))

/**
 * `send_sms` — send an SMS message programmatically via SmsManager. Requires the
 * SEND_SMS runtime permission (re-checked defensively at execute time). Bodies longer
 * than 160 chars are auto-split via SmsManager.divideMessage + sendMultipartTextMessage.
 */
fun smsSendTool(context: Context): Tool = Tool(
    name = "send_sms",
    description = """
        Send an SMS text message to a single recipient programmatically. Requires the
        SEND_SMS permission. Messages longer than 160 characters are automatically split
        into multiple parts. One recipient per call. Returns {success, parts_sent}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("recipient", buildJsonObject {
                    put("type", "string")
                    put("description", "Phone number of the recipient")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Message text (max 4096 characters)")
                })
            },
            required = listOf("recipient", "body"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val recipient = obj["recipient"]?.jsonPrimitive?.contentOrNull
        val body = obj["body"]?.jsonPrimitive?.contentOrNull
        validateSmsArgs(recipient, body)?.let { return@Tool smsErr(it) }

        if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS))) {
            return@Tool smsErr("permission SEND_SMS not granted")
        }

        val smsManager = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (_: Throwable) {
            null
        } ?: return@Tool smsErr("feature unavailable")

        return@Tool try {
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, body, null, null)
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("parts_sent", parts.size)
            }.toString()))
        } catch (e: Throwable) {
            smsErr("send_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)
