package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"

@Serializable
private data class MiniMaxVoiceSetting(
    @SerialName("voice_id") val voiceId: String,
    val speed: Float,
    val vol: Float = 1.0f,
    val pitch: Int = 0,
    val emotion: String
)

@Serializable
private data class MiniMaxAudioSetting(
    @SerialName("sample_rate") val sampleRate: Int = 32000,
    val bitrate: Int = 128000,
    val format: String = "mp3",
    val channel: Int = 1
)

@Serializable
private data class MiniMaxRequestBody(
    val model: String,
    val text: String,
    val stream: Boolean = false,
    @SerialName("output_format") val outputFormat: String = "url",
    @SerialName("voice_setting") val voiceSetting: MiniMaxVoiceSetting,
    @SerialName("audio_setting") val audioSetting: MiniMaxAudioSetting = MiniMaxAudioSetting()
)

@Serializable
private data class MiniMaxResponseData(
    val audio: String,
    val status: Int,
    val ced: String
)

@Serializable
private data class MiniMaxResponse(
    val data: MiniMaxResponseData
)

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = MiniMaxRequestBody(
            model = providerSetting.model,
            text = request.text,
            voiceSetting = MiniMaxVoiceSetting(
                voiceId = providerSetting.voiceId,
                speed = providerSetting.speed,
                emotion = providerSetting.emotion
            )
        )

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/t2a_v2")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(
                json.encodeToString(MiniMaxRequestBody.serializer(), requestBody)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            throw Exception("TTS request failed: ${response.code} ${response.message}")
        }

        val responseBody = response.body.string()
        Log.i(TAG, "MiniMax response: $responseBody")

        val miniMaxResponse = json.decodeFromString<MiniMaxResponse>(responseBody)

        if (miniMaxResponse.data.status != 2) {
            throw Exception("TTS generation failed with status: ${miniMaxResponse.data.status}")
        }

        val audioUrl = miniMaxResponse.data.audio
        Log.i(TAG, "Downloading audio from: $audioUrl")

        // Download the audio file from the URL
        val audioRequest = Request.Builder()
            .url(audioUrl)
            .get()
            .build()

        val audioResponse = httpClient.newCall(audioRequest).await()

        if (!audioResponse.isSuccessful) {
            throw Exception("Audio download failed: ${audioResponse.code} ${audioResponse.message}")
        }

        val audioData = audioResponse.body.bytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "minimax",
                    "model" to providerSetting.model,
                    "voice_id" to providerSetting.voiceId,
                    "emotion" to providerSetting.emotion,
                    "speed" to providerSetting.speed.toString()
                )
            )
        )
    }
}
