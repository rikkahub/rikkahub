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
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.MediaPlaybackService
import java.io.IOException

fun playMediaTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "play_media",
    description = "Start a new playback session from position 0 with system media controls (lock-screen notification, Bluetooth buttons). DESTRUCTIVE — replaces any active session; loses prior playback position. Use only for a brand-new track or 'play X from the start'. To fix an inaudible session use resume_media / seek_media / set_volume — NOT this. Sources: file://, content://, https://. Optional title/artist/album/artwork_uri for the notification.",
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
        wakeScreenIfNeeded(context)
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
        streamer.streamIfHeadless(invocationContext, "PlayMedia: ${source.take(60)}")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun stopMediaTool(context: Context): Tool = Tool(
    name = "stop_media",
    description = "Stop the active media session and dismiss the notification. Use this only when the user is DONE with the track and is moving on to something else (\"stop the music\", \"end playback\"). For \"hold on\" / \"I'll be right back\" / \"pause for a sec\" use pause_media instead — that preserves the live MediaPlayer and lets resume_media continue exactly where the user left off. stop_media tears the player down; resume_media's fallback can replay the last-stopped track from the saved position, but it's a best-effort recovery, not the primary path.",
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
    description = "Resume playback. Primary path: continues the active media session at its current position (after pause_media or seek_media) — does NOT restart from 0. Fallback path: if the session was torn down by stop_media, restarts the last-stopped track at the saved position so the user gets close to \"resume\" semantics even when stop was called. Always prefer this over play_media when continuing.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val svc = MediaPlaybackService.instance
        if (svc != null) {
            // Live session — just nudge ACTION_PLAY (no source extra → resumePlayback).
            val state = if (svc.isPlaying) {
                "already_playing"
            } else {
                val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply {
                    action = MediaPlaybackService.ACTION_PLAY
                }
                context.startService(intent)
                "playing"
            }
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("state", state)
            }.toString()))
        }
        // No live session — fall back to the post-stop snapshot if we have one.
        val snap = MediaPlaybackService.lastStoppedSnapshot
        if (snap != null) {
            val intent = MediaPlaybackService.buildPlayIntent(
                context = context,
                source = snap.source,
                title = snap.title,
                artist = snap.artist,
                album = snap.album,
                artworkUri = snap.artworkUri,
                startPositionMs = snap.positionMs,
            )
            ContextCompat.startForegroundService(context, intent)
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("state", "resumed_from_snapshot")
                put("source", snap.source)
                put("position_ms", snap.positionMs)
            }.toString()))
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", false)
            put("state", "no_session")
        }.toString()))
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
