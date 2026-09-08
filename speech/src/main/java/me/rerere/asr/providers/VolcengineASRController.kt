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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.asr.VOLCENGINE_ASR_WEBSOCKET_URL
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.uuid.Uuid

private const val TAG = "VolcengineASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L

class VolcengineASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Volcengine
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var finishJob: Job? = null

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (provider.websocketUrl.trim().trimEnd('/') == "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel") {
            setError("The Volcengine ASR endpoint is outdated. Change the WebSocket URL to $VOLCENGINE_ASR_WEBSOCKET_URL in settings.")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(provider.websocketUrl)
            .addHeader("X-Api-Key", provider.apiKey)
            .addHeader("X-Api-Resource-Id", provider.resourceId)
            .addHeader("X-Api-Request-Id", Uuid.random().toString())
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (this@VolcengineASRController.webSocket !== webSocket || state.value.status != ASRStatus.Connecting) {
                        webSocket.cancel()
                        return@launch
                    }
                    if (!webSocket.send(VolcengineASRProtocol.initialFrame(provider).toByteString())) {
                        setError("Failed to initialize ASR session")
                        return@launch
                    }
                    _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                    startRecorder(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    if (this@VolcengineASRController.webSocket === webSocket) {
                        handleBinaryResponse(webSocket, bytes.toByteArray())
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    if (this@VolcengineASRController.webSocket !== webSocket) return@launch
                    Log.e(TAG, "Volcengine ASR websocket failed", t)
                    setError(t.message ?: "ASR websocket failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (this@VolcengineASRController.webSocket !== webSocket) return@launch
                    this@VolcengineASRController.webSocket = null
                    finishJob?.cancel()
                    pauseCapture()
                    _state.update { it.copy(status = ASRStatus.Idle) }
                }
            }
        })
    }

    override fun pauseCapture() {
        recorderJob?.cancel()
        runCatching { audioRecord?.stop() }
    }

    override fun stop() {
        if (state.value.status == ASRStatus.Stopping) return
        val wasListening = state.value.status == ASRStatus.Listening
        val recording = recorderJob
        val socket = webSocket
        pauseCapture()
        if (socket == null) {
            _state.update { it.copy(status = ASRStatus.Idle) }
            return
        }
        _state.update { it.copy(status = ASRStatus.Stopping) }
        finishJob?.cancel()
        finishJob = scope.launch {
            recording?.join()
            if (webSocket !== socket) return@launch
            if (!wasListening) {
                webSocket = null
                socket.cancel()
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }
            // The capture loop must finish before the last packet, with no later audio frames.
            if (!socket.send(VolcengineASRProtocol.audioFrame(ByteArray(0), last = true).toByteString())) {
                setError("Failed to finish ASR session")
                return@launch
            }
            delay(10_000)
            if (webSocket === socket) setError("Timed out waiting for final ASR result")
        }
    }

    override fun dispose() {
        pauseCapture()
        finishJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.cancel()
        scope.cancel()
    }

    private fun handleBinaryResponse(socket: WebSocket, data: ByteArray) {
        try {
            val response = VolcengineASRProtocol.decode(data)
            response.error?.let { setError(it); return }
            val text = response.result?.get("text")?.jsonPrimitive?.contentOrNull
            _state.update {
                it.copy(
                    transcript = text ?: it.transcript,
                    voiceTurn = VolcengineASRProtocol.voiceTurn(it.voiceTurn, response.result),
                )
            }
            if (text != null) onTranscriptChange?.invoke(text)
            if (response.isLast) {
                finishJob?.cancel()
                pauseCapture()
                socket.close(1000, "recognition finished")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode ASR response", e)
            setError(e.message ?: "Invalid ASR response")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val chunkSize = (SAMPLE_RATE * 2 * 200 / 1000).coerceAtLeast(minBufferSize)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                chunkSize * 2
            )
            audioRecord = recorder

            try {
                ensureActive()
                recorder.startRecording()
                val buffer = ByteArray(chunkSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            val frame = VolcengineASRProtocol.audioFrame(buffer.copyOfRange(0, read))
                            socket.send(frame.toByteString())
                        } else {
                            Log.w(TAG, "WebSocket queue full, dropping audio frame")
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (isActive) scope.launch(Dispatchers.Main.immediate) {
                    if (webSocket === socket) setError(e.message ?: "Audio recording failed")
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    private fun setError(message: String) {
        pauseCapture()
        finishJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.cancel()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
