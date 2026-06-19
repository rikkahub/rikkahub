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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "StepASR"

// /v1/audio/transcriptions 端点要求上传文件, 官方 SDK 限制 100MB. 我们提前在 6MB
// 触发分段, 与 MiMo 一致, 避免单段过大导致网络传输失败.
private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

// 最小段长度: 16kHz/16bit/mono 下 100ms = 3200 字节。短于这个长度直接丢弃,
// 避免把超短碎片 (比如 stop 时缓冲区里的残留) 发给服务端导致 400。
private const val MIN_SEGMENT_BYTES = 3200

// HTTP 请求失败时的最大重试次数 (含首次). 主要用于偶发 400/网络抖动的自动恢复.
private const val MAX_RETRY = 3

/**
 * 阶跃星辰 Step ASR Controller。
 *
 * 与 MiMo 类似也是 HTTP 一次性提交 + 分段上传, 走 OpenAI 兼容的
 * `POST /v1/audio/transcriptions` 端点 (官方 Android SDK 也用这个端点):
 * - multipart form: model / response_format=json / file=<wav> / hotwords (可选)
 * - 鉴权: `Authorization: Bearer sk-xxx`
 * - 响应: `{"text": "..."}` 简单 JSON
 *
 * 注意 transcriptions 端点只接受 WAV/MP3/OGG/m4a 等容器格式, 不直接收 PCM,
 * 所以跟 MiMo 一样要把 PCM 包成 WAV 再上传。
 *
 * 官方文档: https://platform.stepfun.com/docs/zh/api-reference/audio/asr-sse
 * 官方 SDK: https://github.com/stepfun-ai/stepfunApi-audio-sdk
 */
class StepASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Step
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 同一时刻只允许一个 flush 协程在跑, 避免乱序拼结果
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

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

        // Step 是 HTTP 一次性接口, 没有 WebSocket 连接阶段, 直接进入 Listening
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true
            )
        }
        startRecorder()
    }

    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }

        // 把剩余 PCM 做最后一次 flush, 完成后切回 Idle
        scope.launch(Dispatchers.IO) {
            try {
                // 等当前正在跑的 flushJob 完成, 避免并发 flush 导致缓冲区竞争
                flushJob?.join()
                flushSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "Step ASR final flush failed")
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle) }
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
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            if (segmentMs <= 0) {
                                currentBuffer.size() >= MAX_SEGMENT_BYTES
                            } else {
                                val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                                currentBuffer.size() >= MAX_SEGMENT_BYTES || elapsed >= segmentMs
                            }
                        }

                        if (shouldFlush) {
                            // 用单独协程异步 flush, 不阻塞录音主循环
                            triggerFlush()
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun triggerFlush() {
        // 同一时刻只跑一个 flush, 避免后发先至导致结果乱序
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            runCatching { flushSegment() }
                .onFailure { Log.e(TAG, "Segment flush failed", it) }
        }
    }

    /**
     * 取出当前缓冲区里的 PCM, 包成 WAV, 用 multipart 上传到 Step /v1/audio/transcriptions。
     * 响应是简单 JSON: {"text": "..."}, 把识别结果加到 completedTranscripts。
     *
     * 在 bufferLock 内拷贝出 PCM 并立刻重置缓冲区, 不持有锁等待网络, 避免阻塞录音写。
     */
    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        // 太短的段直接丢弃, 避免服务端因音频过短返回 400
        // (16kHz/16bit/mono 下 6400 字节 = 200ms, 短于这个长度服务端通常无法识别)
        if (pcmBytes.size < MIN_SEGMENT_BYTES) {
            Log.d(TAG, "Skip flush: PCM too short (${pcmBytes.size} bytes)")
            return
        }

        // Step transcriptions 端点只接受 WAV/MP3 等容器, 不收 raw PCM,
        // 跟 MiMo 一样包 44 字节 WAV 头
        val wavBytes = pcm16ToWav(
            pcm = pcmBytes,
            sampleRate = provider.sampleRate,
            channels = 1,
            bitsPerSample = 16
        )

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                "audio.wav",
                wavBytes.toRequestBody(WAV_MEDIA_TYPE)
            )

        if (provider.hotwords.isNotEmpty()) {
            bodyBuilder.addFormDataPart(
                "hotwords",
                JSONArray(provider.hotwords).toString()
            )
        }

        val request = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .post(bodyBuilder.build())
            .build()

        // 偶发 400 (服务端解析异常) / 网络抖动时自动重试, 避免用户感知到失败.
        // 实测同样语音重说一次就能成功, 说明大部分 400 是临时性的, 重试可以解决.
        var lastError: IOException? = null
        var text = ""
        for (attempt in 1..MAX_RETRY) {
            try {
                text = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IOException("Step ASR HTTP ${resp.code}: $respBody")
                        }
                        val json = runCatching { JSONObject(respBody) }.getOrElse {
                            throw IOException("Step ASR response is not valid JSON: $respBody")
                        }
                        json.optString("text").trim()
                    }
                }
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "flushSegment attempt $attempt/$MAX_RETRY failed: ${e.message}")
                if (attempt < MAX_RETRY) {
                    kotlinx.coroutines.delay(300L * attempt) // 指数退避: 300ms, 600ms
                }
            }
        }
        if (lastError != null) throw lastError

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

    companion object {
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        /**
         * 把 raw PCM16 little-endian 数据封装成最小 WAV (RIFF/WAVE/fmt/data)。
         * transcriptions 端点只接受容器格式, 不收 raw PCM。
         */
        private fun pcm16ToWav(
            pcm: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val out = ByteArrayOutputStream(44 + dataSize)

            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 36 + dataSize) // chunk size = file size - 8
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16)            // PCM fmt chunk size
            writeShortLE(out, 1)           // audio format = PCM
            writeShortLE(out, channels)
            writeIntLE(out, sampleRate)
            writeIntLE(out, byteRate)
            writeShortLE(out, blockAlign)
            writeShortLE(out, bitsPerSample)
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, dataSize)
            out.write(pcm)
            return out.toByteArray()
        }

        private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
    }
}
