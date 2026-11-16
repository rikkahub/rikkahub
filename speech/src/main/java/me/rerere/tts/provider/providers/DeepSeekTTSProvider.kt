package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "DeepSeekTTSProvider"
private const val PCM_SAMPLE_RATE = 24000
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * DeepSeek 语音合成 (REST 整段合成)。
 *
 * [POST {baseUrl}/v1/tools/tts], body 为 `{text, voice_id, format}`。音频在响应体里流式返回,
 * 这里读完整个响应体再发出: 若服务端中途失败会直接断开连接, 读取会抛异常, 不会把半段音频当成功。
 * 请求 pcm (24kHz / 16bit / mono), 由 AudioPlayer 补 WAV 头, 避免流式 WAV 头里长度字段不确定。
 *
 * 官方文档: https://api-docs.deepseek.com/zh-cn/guides/tts
 */
class DeepSeekTTSProvider : TTSProvider<TTSProviderSetting.DeepSeek> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.DeepSeek,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            put("text", request.text)
            put("voice_id", providerSetting.voiceId)
            put("format", "pcm")
        }

        Log.i(TAG, "generateSpeech: voice=${providerSetting.voiceId} length=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/v1/tools/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val audioBytes = httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = runCatching { response.body.string() }.getOrNull().orEmpty()
                throw TTSProviderException(
                    message = "DeepSeek TTS request failed: HTTP ${response.code} ${response.message}. body=$errorBody",
                    statusCode = response.code
                )
            }
            response.body.bytes()
        }

        if (audioBytes.isEmpty()) {
            throw Exception("DeepSeek TTS returned 0 bytes")
        }

        emit(
            AudioChunk(
                data = audioBytes,
                format = AudioFormat.PCM,
                sampleRate = PCM_SAMPLE_RATE,
                isLast = true,
                metadata = mapOf(
                    "provider" to "deepseek",
                    "voice" to providerSetting.voiceId,
                )
            )
        )
    }.flowOn(Dispatchers.IO)
}
