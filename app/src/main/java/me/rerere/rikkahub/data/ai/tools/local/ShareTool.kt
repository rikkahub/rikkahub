package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

fun shareTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "share",
    description = """
        Open the system share sheet so the user can send text or a URL to another app
        (messages, email, etc.). At least one of text or url must be provided.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content to share")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to share")
                })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional subject (e.g., for email)")
                })
            }
        )
    },
    execute = {
        wakeScreenIfNeeded(context)
        val params = it.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotEmpty() }
        val url = params["url"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotEmpty() }
        val subject = params["subject"]?.jsonPrimitive?.contentOrNull

        if (text == null && url == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "provide at least one of text or url") }.toString()
                )
            )
        }

        val combined = listOfNotNull(text, url).joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, combined)
            if (!subject.isNullOrEmpty()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
        }
        val chooser = Intent.createChooser(intent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)

        val payload = buildJsonObject { put("success", true) }
        val shareLabel = combined.take(50)
        streamer.streamIfHeadless(invocationContext, "Share: $shareLabel")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
