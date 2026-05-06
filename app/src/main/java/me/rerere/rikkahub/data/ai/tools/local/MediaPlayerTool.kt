package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.MediaPlaybackService
import java.io.IOException

fun playMediaTool(context: Context): Tool = Tool(
    name = "play_media",
    description = """
        START A NEW PLAYBACK SESSION from position 0. Shows system media controls (lock
        screen notification, Bluetooth headset buttons). Replaces any audio currently
        playing from this tool — DESTRUCTIVE: existing session is stopped and the new
        track starts from the beginning.
        DO NOT use this to "fix" an active session that the user can't hear — that
        loses the user's playback position. Use resume_media (continue paused), seek_media
        (jump to position), set_volume (raise audio), or get_audio_info (diagnose) instead.
        Only use play_media when starting a brand-new track or when the user explicitly
        says "play X from the start".
        Supports file://, content://, and https:// sources. Optional title/artist/album/
        artwork_uri populate the notification metadata.
        CRITICAL — NO HALLUCINATION: play_media plays audio TO THE USER'S DEVICE SPEAKER.
        It does NOT give the agent access to the audio content. To get the words inside an
        audio file, use transcribe_audio_file(path). Calling play_media on a voice note and
        then claiming to know what was said is a hallucination — refuse to do it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "File path or URL to play")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Track title for the media notification (optional)")
                })
                put("artist", buildJsonObject {
                    put("type", "string")
                    put("description", "Artist name for the media notification (optional)")
                })
                put("album", buildJsonObject {
                    put("type", "string")
                    put("description", "Album name for the media notification (optional)")
                })
                put("artwork_uri", buildJsonObject {
                    put("type", "string")
                    put("description", "URI for album artwork image (optional, file:// or https://)")
                })
            },
            required = listOf("source")
        )
    },
    execute = {
        val params = it.jsonObject
        val source = params["source"]?.jsonPrimitive?.contentOrNull
            ?: error("source is required")
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val artist = params["artist"]?.jsonPrimitive?.contentOrNull
        val album = params["album"]?.jsonPrimitive?.contentOrNull
        val artworkUri = params["artwork_uri"]?.jsonPrimitive?.contentOrNull

        val payload = try {
            val intent = MediaPlaybackService.buildPlayIntent(
                context, source, title, artist, album, artworkUri
            )
            ContextCompat.startForegroundService(context, intent)
            buildJsonObject {
                put("success", true)
                put("source", source)
                put("session_active", true)
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

fun stopMediaTool(context: Context): Tool = Tool(
    name = "stop_media",
    description = "Stop any audio currently playing via play_media and dismiss the media notification.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val wasPlaying = MediaPlaybackService.instance?.isPlaying ?: false
        val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_STOP
        }
        context.startService(intent)
        val payload = buildJsonObject {
            put("success", true)
            put("was_playing", wasPlaying)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun pauseMediaTool(context: Context): Tool = Tool(
    name = "pause_media",
    description = "Pause audio currently playing via play_media. Use resume_media to continue.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val svc = MediaPlaybackService.instance
        val state = when {
            svc == null -> "no_session"
            !svc.isPlaying -> "already_paused"
            else -> {
                val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply {
                    action = MediaPlaybackService.ACTION_PAUSE
                }
                context.startService(intent)
                "paused"
            }
        }
        val payload = buildJsonObject {
            put("success", state != "no_session")
            put("state", state)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun resumeMediaTool(context: Context): Tool = Tool(
    name = "resume_media",
    description = "Resume the active media session at its current position (after pause_media or after seek_media). Does NOT restart from 0 — preserves the playback position. Use this — not play_media — whenever continuing an existing session.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val svc = MediaPlaybackService.instance
        val state = when {
            svc == null -> "no_session"
            svc.isPlaying -> "already_playing"
            else -> {
                val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply {
                    action = MediaPlaybackService.ACTION_PLAY
                }
                context.startService(intent)
                "playing"
            }
        }
        val payload = buildJsonObject {
            put("success", state != "no_session")
            put("state", state)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun seekMediaTool(context: Context): Tool = Tool(
    name = "seek_media",
    description = "Jump to a specific position (milliseconds from start) in the active media session. Works whether playing or paused — preserves the play/pause state. Pair with resume_media if currently paused. NEVER use play_media to seek; that wipes the session and restarts from 0.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("position_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Target position in milliseconds")
                })
            },
            required = listOf("position_ms")
        )
    },
    execute = {
        val posMs = it.jsonObject["position_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: error("position_ms is required")
        val svc = MediaPlaybackService.instance
        if (svc == null) {
            val payload = buildJsonObject {
                put("success", false)
                put("error", "no_session")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply {
                action = MediaPlaybackService.ACTION_SEEK
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, posMs)
            }
            context.startService(intent)
            val payload = buildJsonObject {
                put("success", true)
                put("position_ms", posMs)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    }
)

fun getMediaStatusTool(): Tool = Tool(
    name = "get_media_status",
    description = "Get the current media playback status: whether something is playing, the source, position, duration, and metadata.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val svc = MediaPlaybackService.instance
        val payload = if (svc == null) {
            buildJsonObject { put("playing", false) }
        } else {
            buildJsonObject {
                put("playing", svc.isPlaying)
                svc.currentSource?.let { put("source", it) }
                put("position_ms", svc.readCurrentPositionMs())
                put("duration_ms", svc.durationMs)
                svc.currentTitle?.let { put("title", it) }
                svc.currentArtist?.let { put("artist", it) }
                svc.currentAlbum?.let { put("album", it) }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
