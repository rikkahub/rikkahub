package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileVoiceSessionResponse
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileApi

interface VoiceSessionApi {
    suspend fun createSession(modelId: String): MobileVoiceSessionResponse
}

class VoiceLabVoiceSessionApi(
    private val api: VoiceLabMobileApi,
) : VoiceSessionApi {
    override suspend fun createSession(modelId: String): MobileVoiceSessionResponse =
        api.createSession(modelId = modelId)
}

interface VoiceToolApi {
    suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse
}

class VoiceLabHermesToolApi(
    private val api: VoiceLabMobileApi,
    private val profileId: String? = null,
) : VoiceToolApi {
    override suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse =
        api.askHermes(callId = callId, prompt = prompt, profileId = profileId)
}
