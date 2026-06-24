package me.rerere.tts.provider.providers

import android.content.Context
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

private const val TAG = "ElevenLabsTTSProvider"

class ElevenLabsTTSProvider : TTSProvider<TTSProviderSetting.ElevenLabs> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("text", request.text)
            put("model_id", providerSetting.model)
            put("voice_settings", JSONObject().apply {
                put("stability", providerSetting.stability.toDouble())
                put("similarity_boost", providerSetting.similarityBoost.toDouble())
            })
        }

        Log.i(TAG, "generateSpeech: model=${providerSetting.model}, voiceId=${providerSetting.voiceId}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/v1/text-to-speech/${providerSetting.voiceId}?output_format=mp3_44100_128")
            .addHeader("xi-api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            Log.e(TAG, "generateSpeech: ${response.code} ${response.message}")
            Log.e(TAG, "generateSpeech: $errorBody")
            throw Exception("ElevenLabs TTS request failed: ${response.code} ${response.message}")
        }

        val audioData = response.body.bytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "elevenlabs",
                    "model" to providerSetting.model,
                    "voiceId" to providerSetting.voiceId
                )
            )
        )
    }
}
