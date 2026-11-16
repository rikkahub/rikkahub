package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.asr.pcm16ToWav
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "DeepSeekASR"

// 官方上限: 单次音频 ≤ 120 秒且文件 ≤ 8 MiB。各留一点余量, 到线就强制切段。
private const val MAX_SEGMENT_SECONDS = 110
private const val MAX_SEGMENT_BYTES = 7 * 1024 * 1024

private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

/**
 * DeepSeek ASR Controller。
 *
 * DeepSeek ASR 是非流式 HTTP 接口: multipart 上传一段完整音频, 一次返回整段文本。
 * 录音期间按 [ASRProviderSetting.DeepSeek.segmentDurationSec] 或单次上限把 PCM 切段,
 * 每段封装成 WAV 后 POST 到 {baseUrl}/v1/tools/asr, 结果依次拼接。stop() 时上传剩余部分。
 *
 * 官方文档: https://api-docs.deepseek.com/zh-cn/guides/asr
 */
class DeepSeekASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.DeepSeek
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 同一时刻只跑一个上传, 保证结果按录音顺序拼接
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    private val maxSegmentBytes: Int
        get() = (provider.sampleRate * 2 * MAX_SEGMENT_SECONDS).coerceAtMost(MAX_SEGMENT_BYTES)

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
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        flushJob = null

        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true
            )
        }
        startRecorder()
    }

    override fun stop() {
        if (_state.value.status != ASRStatus.Listening) return
        val recorder = recorderJob
        _state.update { it.copy(status = ASRStatus.Stopping) }

        scope.launch(Dispatchers.IO) {
            // 等录音循环真正退出, 确保最后一块 PCM 已写进缓冲区
            recorder?.cancelAndJoin()
            releaseRecorder()
            try {
                flushJob?.join()
                flushSegment()
                _state.update { it.copy(status = ASRStatus.Idle) }
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "DeepSeek ASR request failed")
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                val segmentMs = provider.segmentDurationSec.coerceAtLeast(0) * 1000L
                val maxBytes = maxSegmentBytes
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                            currentBuffer.size() >= maxBytes || (segmentMs > 0 && elapsed >= segmentMs)
                        }
                        if (shouldFlush) triggerFlush()
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    private fun triggerFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            runCatching { flushSegment() }
                .onFailure { e ->
                    Log.e(TAG, "Segment flush failed", e)
                    // 录音仍在继续, 只提示错误, 不切换状态
                    _state.update { it.copy(errorMessage = e.message ?: "DeepSeek ASR request failed") }
                }
        }
    }

    /**
     * 取出当前缓冲区的 PCM 上传识别。锁内只做拷贝与重置, 网络请求不持锁, 避免阻塞录音。
     */
    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        val wavBytes = pcm16ToWav(pcm = pcmBytes, sampleRate = provider.sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wavBytes.toRequestBody(WAV_MEDIA_TYPE))
            .build()

        val request = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/v1/tools/asr")
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .post(body)
            .build()

        val text = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { resp ->
                val respBody = resp.body.string()
                if (!resp.isSuccessful) {
                    throw IOException("DeepSeek ASR HTTP ${resp.code}: $respBody")
                }
                val json = runCatching { JSONObject(respBody) }.getOrElse {
                    throw IOException("DeepSeek ASR response is not valid JSON: $respBody")
                }
                json.optString("text", "").trim()
            }
        }

        if (text.isNotEmpty()) {
            completedTranscripts.add(text)
            publishTranscript()
        }
    }

    private fun publishTranscript() {
        val transcript = completedTranscripts
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}
