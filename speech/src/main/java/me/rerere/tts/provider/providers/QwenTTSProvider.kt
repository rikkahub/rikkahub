package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "QwenTTSProvider"

class QwenTTSProvider : TTSProvider<TTSProviderSetting.Qwen> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Qwen,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", JSONObject().apply {
                put("text", request.text)
                put("voice", providerSetting.voice)
                put("language_type", providerSetting.languageType)
            })
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/services/aigc/multimodal-generation/generation")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "enable")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            Log.e(TAG, "Qwen TTS request failed: ${response.code} ${response.message}, body: $errorBody")
            throw Exception("Qwen TTS request failed: ${response.code} ${response.message}")
        }

        val reader = response.body.byteStream().bufferedReader()

        try {
            var currentData = StringBuilder()

            reader.lineSequence().forEach { line ->
                when {
                    line.startsWith("data:") -> {
                        currentData.append(line.removePrefix("data:"))
                    }

                    line.isEmpty() && currentData.isNotEmpty() -> {
                        val result = parseSSEData(currentData.toString())
                        if (result != null) {
                            val (audioData, isLast) = result
                            emit(
                                AudioChunk(
                                    data = audioData,
                                    format = AudioFormat.PCM,
                                    sampleRate = 24000,
                                    isLast = isLast,
                                    metadata = mapOf(
                                        "provider" to "qwen",
                                        "model" to providerSetting.model,
                                        "voice" to providerSetting.voice,
                                        "sampleRate" to "24000",
                                        "channels" to "1",
                                        "bitDepth" to "16"
                                    )
                                )
                            )
                        }
                        currentData = StringBuilder()
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun parseSSEData(data: String): Pair<ByteArray, Boolean>? {
        return try {
            val json = JSONObject(data)
            val output = json.optJSONObject("output") ?: return null
            val audio = output.optJSONObject("audio") ?: return null
            val audioBase64 = audio.optString("data", "")
            val finishReason = output.optString("finish_reason", "")

            if (audioBase64.isNotEmpty()) {
                val audioData = Base64.decode(audioBase64, Base64.DEFAULT)
                val isLast = finishReason == "stop"
                Pair(audioData, isLast)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SSE data: $data", e)
            null
        }
    }
}
