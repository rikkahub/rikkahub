package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiTTSProvider"

class GeminiTTSProvider : TTSProvider<TTSProviderSetting.Gemini> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GeminiTTSResponse(
        val candidates: List<Candidate>
    )

    @Serializable
    data class Candidate(
        val content: Content
    )

    @Serializable
    data class Content(
        val parts: List<Part>
    )

    @Serializable
    data class Part(
        val inlineData: InlineData
    )

    @Serializable
    data class InlineData(
        val data: String,
        val mimeType: String
    )

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Gemini,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", request.text)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", providerSetting.voiceName)
                        })
                    })
                })
            })
            put("model", providerSetting.model)
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/models/${providerSetting.model}:generateContent")
            .addHeader("x-goog-api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            throw Exception("Gemini TTS request failed: ${response.code} ${response.message}")
        }

        val responseJson = response.body.string()
        val geminiResponse = json.decodeFromString<GeminiTTSResponse>(responseJson)

        if (geminiResponse.candidates.isEmpty() ||
            geminiResponse.candidates[0].content.parts.isEmpty()
        ) {
            throw Exception("No audio data returned from Gemini TTS")
        }

        val audioBase64 = geminiResponse.candidates[0].content.parts[0].inlineData.data
        val audioData = Base64.decode(audioBase64, Base64.DEFAULT)

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.PCM,
                sampleRate = 24000, // Gemini TTS returns 24kHz 16-bit mono PCM
                isLast = true,
                metadata = mapOf(
                    "provider" to "gemini",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voiceName,
                    "sampleRate" to "24000",
                    "channels" to "1",
                    "bitDepth" to "16"
                )
            )
        )
    }
}
