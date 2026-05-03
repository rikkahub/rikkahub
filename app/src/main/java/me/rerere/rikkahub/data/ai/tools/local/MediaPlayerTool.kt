package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.IOException

fun playMediaTool(holder: MediaPlayerHolder): Tool = Tool(
    name = "play_media",
    description = """
        Start playing audio from a file path or URL. Replaces any audio currently playing
        from this tool. Note that remote URLs may take several seconds to start due to buffering.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "File path or URL to play")
                })
            },
            required = listOf("source")
        )
    },
    execute = {
        val source = it.jsonObject["source"]?.jsonPrimitive?.contentOrNull
            ?: error("source is required")
        val payload = try {
            holder.play(source)
            buildJsonObject {
                put("success", true)
                put("source", source)
            }
        } catch (e: IOException) {
            buildJsonObject { put("error", e.message ?: "io error") }
        } catch (e: IllegalStateException) {
            buildJsonObject { put("error", e.message ?: "illegal state") }
        } catch (e: IllegalArgumentException) {
            buildJsonObject { put("error", e.message ?: "invalid argument") }
        } catch (e: SecurityException) {
            buildJsonObject { put("error", e.message ?: "security error") }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun stopMediaTool(holder: MediaPlayerHolder): Tool = Tool(
    name = "stop_media",
    description = "Stop any audio currently playing via play_media.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val wasPlaying = holder.stop()
        val payload = buildJsonObject {
            put("success", true)
            put("was_playing", wasPlaying)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
