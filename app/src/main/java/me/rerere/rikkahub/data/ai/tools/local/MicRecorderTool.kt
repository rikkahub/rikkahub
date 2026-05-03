package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.IOException
import java.util.UUID

fun micRecorderTool(context: Context): Tool = Tool(
    name = "record_audio",
    description = """
        Record audio from the microphone for a bounded duration. Returns the local file path
        of the AAC/m4a recording. Use sparingly — recording without user awareness is intrusive.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Recording duration in milliseconds (default 10000, min 1000, max 300000)")
                })
            }
        )
    },
    execute = {
        if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.RECORD_AUDIO))) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "permission RECORD_AUDIO not granted") }.toString()
                )
            )
        }

        val params = it.jsonObject
        val durationMs = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
            .coerceIn(1_000, 300_000)

        val recordingsDir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val outputFile = File(recordingsDir, "${UUID.randomUUID()}.m4a")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(outputFile.absolutePath)

            try {
                recorder.prepare()
            } catch (e: IOException) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "recorder failed: ${e.message ?: "unknown"}")
                        }.toString()
                    )
                )
            }

            try {
                recorder.start()
            } catch (_: IllegalStateException) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject { put("error", "another recording in progress") }.toString()
                    )
                )
            }

            delay(durationMs.toLong())
        } finally {
            try {
                recorder.stop()
            } catch (_: Throwable) {
                // start() may have failed; stop() can throw. Swallow.
            }
            try {
                recorder.release()
            } catch (_: Throwable) {
                // Best-effort.
            }
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("path", outputFile.absolutePath)
                    put("duration_ms", durationMs)
                }.toString()
            )
        )
    }
)
