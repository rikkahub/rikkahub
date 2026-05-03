package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.media.AudioManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val STREAM_MAP: Map<String, Int> = mapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "notification" to AudioManager.STREAM_NOTIFICATION,
    "alarm" to AudioManager.STREAM_ALARM,
    "voice_call" to AudioManager.STREAM_VOICE_CALL,
    "system" to AudioManager.STREAM_SYSTEM,
)

private fun streamFor(name: String): Int? = STREAM_MAP[name]

fun getVolumeTool(context: Context): Tool = Tool(
    name = "get_volume",
    description = """
        Get the current volume of an audio stream (media, ring, notification, alarm,
        voice_call, system).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "Stream name: media, ring, notification, alarm, voice_call, system (default media)")
                })
            }
        )
    },
    execute = {
        val params = it.jsonObject
        val name = params["stream"]?.jsonPrimitive?.contentOrNull ?: "media"
        val streamInt = streamFor(name)
        if (streamInt == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "unknown stream: $name") }.toString()
                )
            )
        }
        val am = context.getSystemService(AudioManager::class.java)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "AudioManager unavailable") }.toString()
                )
            )
        val volume = am.getStreamVolume(streamInt)
        val max = am.getStreamMaxVolume(streamInt)
        val percent = if (max > 0) (volume * 100 + max / 2) / max else 0
        val payload = buildJsonObject {
            put("stream", name)
            put("volume", volume)
            put("max", max)
            put("percent", percent)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun setVolumeTool(context: Context): Tool = Tool(
    name = "set_volume",
    description = """
        Set the volume of an audio stream (0-100 percent). Setting ring/notification
        streams requires DND access.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "Stream name: media, ring, notification, alarm, voice_call, system")
                })
                put("percent", buildJsonObject {
                    put("type", "integer")
                    put("description", "Target volume as a percentage (0-100)")
                })
            },
            required = listOf("stream", "percent")
        )
    },
    execute = {
        val params = it.jsonObject
        val name = params["stream"]?.jsonPrimitive?.contentOrNull
            ?: error("stream is required")
        val percentRaw = params["percent"]?.jsonPrimitive?.intOrNull
            ?: error("percent is required")
        val streamInt = streamFor(name)
        if (streamInt == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "unknown stream: $name") }.toString()
                )
            )
        }
        if ((name == "ring" || name == "notification") && !PermissionHelper.hasDndAccess(context)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "DND access not granted; cannot modify ring/notification volume")
                    }.toString()
                )
            )
        }
        val am = context.getSystemService(AudioManager::class.java)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "AudioManager unavailable") }.toString()
                )
            )
        val percent = percentRaw.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(streamInt)
        val targetStep = (percent * max + 50) / 100
        val payload = try {
            am.setStreamVolume(streamInt, targetStep, 0)
            buildJsonObject {
                put("success", true)
                put("stream", name)
                put("percent", percent)
            }
        } catch (_: SecurityException) {
            buildJsonObject {
                put("error", "DND access not granted; cannot modify ring/notification volume")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
