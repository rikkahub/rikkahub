package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString

private const val TAG = "AnthropicVoiceASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val KEEPALIVE_INTERVAL_MS = 8_000L

// OAuth (Claude Code) constants — pinned to Claude Code 2.1.x; Anthropic rotates
// these. Mirrors me.rerere.ai.provider.providers.ClaudeProvider so the voice_stream
// WebSocket presents the same fingerprint as the chat client and is not challenged.
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_OAUTH_BETAS =
    "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,context-management-2025-06-27,prompt-caching-scope-2026-01-05,structured-outputs-2025-12-15,fast-mode-2026-02-01,redact-thinking-2026-02-12,token-efficient-tools-2026-03-28"
private const val CLAUDE_CODE_USER_AGENT = "ClaudeCode/2.1.128"

private const val VOICE_STREAM_PATH = "/api/ws/speech_to_text/voice_stream"
private const val KEEPALIVE_MSG = "{\"type\":\"KeepAlive\"}"
private const val CLOSE_STREAM_MSG = "{\"type\":\"CloseStream\"}"

class AnthropicVoiceASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.AnthropicVoice,
    private val oauthToken: String,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var keepAliveJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val accumulator = VoiceTranscriptAccumulator()

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        accumulator.reset()
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(buildVoiceStreamUrl(provider.baseUrl, provider.language, provider.sampleRate))
            .addHeader("Authorization", "Bearer $oauthToken")
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("anthropic-beta", CLAUDE_OAUTH_BETAS)
            .addHeader("User-Agent", CLAUDE_CODE_USER_AGENT)
            .addHeader("x-app", "cli")
            .build()

        // Close any prior socket before opening a new one; its listener callbacks are
        // guarded by isCurrentSocket() so they cannot mutate the new session's state.
        webSocket?.close(1000, "restart")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentSocket(webSocket)) return
                // Audio hardware init can take >1s; send an immediate KeepAlive so the
                // server does not close the connection before the first audio frame.
                webSocket.send(KEEPALIVE_MSG)
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                startKeepAlive(webSocket)
                startRecorder(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentSocket(webSocket)) return
                handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentSocket(webSocket)) return
                Log.e(TAG, "Anthropic Voice ASR websocket failed", t)
                releaseAudio()
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentSocket(webSocket)) return
                val message = closeCodeError(code)
                if (message != null) {
                    releaseAudio()
                    setError(message)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentSocket(webSocket)) return
                releaseAudio()
                if (state.value.status != ASRStatus.Error) {
                    _state.update {
                        it.copy(
                            status = ASRStatus.Idle,
                            errorMessage = null
                        )
                    }
                }
            }
        })
    }

    private fun isCurrentSocket(socket: WebSocket): Boolean = webSocket === socket

    override fun stop() {
        recorderJob?.cancel()
        keepAliveJob?.cancel()
        releaseAudio()
        val socket = webSocket
        if (socket != null) {
            _state.update { it.copy(status = ASRStatus.Stopping) }
            scope.launch {
                socket.send(CLOSE_STREAM_MSG)
                delay(500)
                socket.close(1000, "stop")
                if (webSocket === socket) {
                    webSocket = null
                    if (state.value.status != ASRStatus.Error) {
                        _state.update { it.copy(status = ASRStatus.Idle) }
                    }
                }
            }
        } else {
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        keepAliveJob?.cancel()
        releaseAudio()
        // Close synchronously: scope.cancel() below would kill stop()'s deferred
        // close coroutine mid-delay, leaking the socket and its server-side stream.
        val socket = webSocket
        webSocket = null
        socket?.send(CLOSE_STREAM_MSG)
        socket?.close(1000, "dispose")
        scope.cancel()
    }

    private fun startKeepAlive(socket: WebSocket) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                socket.send(KEEPALIVE_MSG)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(provider.sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            socket.send(buffer.toByteString(0, read))
                        } else {
                            Log.w(TAG, "WebSocket queue full, dropping audio frame")
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: CancellationException) {
                // Normal stop()/dispose() cancellation — not an error. Re-throw so the
                // coroutine completes cancelled and does not flip ASR into Error state.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                teardownAndError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun handleServerEvent(text: String) {
        when (val frame = parseVoiceFrame(text)) {
            is VoiceFrame.TranscriptText -> {
                accumulator.onInterim(frame.text)
                publishTranscript()
            }

            VoiceFrame.TranscriptEndpoint -> {
                accumulator.onEndpoint()
                publishTranscript()
            }

            is VoiceFrame.TranscriptError -> {
                teardownAndError(frame.description)
            }

            VoiceFrame.Ignored -> {
                Log.v(TAG, "Ignored voice_stream frame")
            }

            VoiceFrame.Invalid -> {
                Log.w(TAG, "Invalid voice_stream frame: $text")
            }
        }
    }

    private fun publishTranscript() {
        val transcript = accumulator.transcript()
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch {
            onTranscriptChange?.invoke(transcript)
        }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    // Fully terminate a still-running session, then surface the error. A fatal frame
    // (server TranscriptError) or a recorder-loop failure leaves the socket open and
    // the keepAlive/recorder coroutines live; Error must be a true terminal state, so
    // tear down the socket + audio before flipping state instead of layering Error
    // over a session that keeps pinging a dead stream and recording.
    private fun teardownAndError(message: String) {
        releaseAudio()
        val socket = webSocket
        webSocket = null
        socket?.close(1000, "error")
        setError(message)
    }

    private fun releaseAudio() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        releaseRecorder()
    }

    private fun releaseRecorder() {
        // Cancel the IO loop before tearing down the AudioRecord so the loop can never
        // race into recorder.read() on a released recorder (which returns < 0 and would
        // otherwise flip a normal close into Error). Safe to call from the loop's own
        // finally — the job is already completing there.
        recorderJob?.cancel()
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}

internal sealed interface VoiceFrame {
    data class TranscriptText(val text: String) : VoiceFrame
    data object TranscriptEndpoint : VoiceFrame
    data class TranscriptError(val description: String) : VoiceFrame
    data object Ignored : VoiceFrame
    data object Invalid : VoiceFrame
}

// Accumulates the running transcript from interim/endpoint frames. The server
// sends progressively refined interim text per utterance; an endpoint commits the
// current interim as a completed segment so the next utterance does not overwrite it.
internal class VoiceTranscriptAccumulator {
    private val completed = mutableListOf<String>()
    private var interim: String = ""

    fun reset() {
        completed.clear()
        interim = ""
    }

    fun onInterim(text: String) {
        interim = text
    }

    fun onEndpoint() {
        val finalText = interim.trim()
        interim = ""
        if (finalText.isNotEmpty()) {
            completed.add(finalText)
        }
    }

    fun transcript(): String =
        (completed + interim)
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.trim() }
}

// Anthropic's voice_stream proxies to Deepgram, which expects a Deepgram language
// code. Empty/auto maps to "multi" (Deepgram multilingual). Otherwise the lower-case
// code is passed through unchanged (e.g. "en", "zh", "es").
internal fun toDeepgramLang(language: String): String {
    val normalized = language.trim().lowercase()
    return when (normalized) {
        "", "auto" -> "multi"
        else -> normalized
    }
}

internal fun buildVoiceStreamUrl(baseUrl: String, language: String, sampleRate: Int): String {
    // Build with HttpUrl so query values are percent-encoded — language is
    // user-editable and toDeepgramLang only lowercases/trims, so a value like
    // "en&endpointing_ms=0" must not be able to inject/override query params.
    // HttpUrl only parses http(s); rewrite to ws(s) on the encoded result.
    val httpBase = baseUrl.trim().trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
    val url = httpBase.toHttpUrl().newBuilder()
        .addPathSegments(VOICE_STREAM_PATH.trimStart('/'))
        .addQueryParameter("encoding", "linear16")
        .addQueryParameter("sample_rate", sampleRate.toString())
        .addQueryParameter("channels", "1")
        .addQueryParameter("endpointing_ms", "300")
        .addQueryParameter("utterance_end_ms", "1000")
        .addQueryParameter("language", toDeepgramLang(language))
        .build()
        .toString()
    return url
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
}

private val voiceFrameJson = Json { ignoreUnknownKeys = true }

private fun kotlinx.serialization.json.JsonObject.stringField(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun parseVoiceFrame(text: String): VoiceFrame {
    val obj = runCatching { voiceFrameJson.parseToJsonElement(text).jsonObject }
        .getOrNull() ?: return VoiceFrame.Invalid
    return when (obj.stringField("type")) {
        "TranscriptText" -> VoiceFrame.TranscriptText(obj.stringField("data"))
        "TranscriptEndpoint" -> VoiceFrame.TranscriptEndpoint
        "TranscriptError" -> {
            val description = obj.stringField("description")
                .ifBlank { obj.stringField("error_code") }
                .ifBlank { "Transcription error" }
            VoiceFrame.TranscriptError(description)
        }

        "error" -> {
            val description = obj.stringField("message").ifBlank { "Voice stream error" }
            VoiceFrame.TranscriptError(description)
        }

        else -> VoiceFrame.Ignored
    }
}

// The server signals fatal auth/permission failures via WebSocket close codes.
// 1008 (policy violation) and the Anthropic 44xx range map to a surfaced error;
// all other codes (e.g. 1000 normal) are non-fatal.
internal fun closeCodeError(code: Int): String? = when (code) {
    1008 -> "Voice stream rejected (policy violation)"
    4401 -> "Voice stream authentication failed"
    4403 -> "Voice stream access forbidden"
    else -> null
}
