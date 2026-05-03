package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun toastTool(context: Context): Tool = Tool(
    name = "show_toast",
    description = """
        Show a brief toast popup over whatever is currently on screen.
        Use sparingly — toasts are intrusive and only useful for short, momentary feedback.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to display in the toast")
                })
                put("long", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Use long duration toast (default false)")
                })
            },
            required = listOf("text")
        )
    },
    execute = {
        val params = it.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: error("text is required")
        val long = params["long"]?.jsonPrimitive?.booleanOrNull ?: false
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
        val payload = buildJsonObject { put("success", true) }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
