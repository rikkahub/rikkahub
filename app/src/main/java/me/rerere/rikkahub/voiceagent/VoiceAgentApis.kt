package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobSubmitResponse
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
    suspend fun submitHermesJob(callId: String, prompt: String): MobileHermesJobSubmitResponse
    suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse
    suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse
}

class VoiceLabHermesToolApi(
    private val api: VoiceLabMobileApi,
    private val profileId: String? = null,
) : VoiceToolApi {
    override suspend fun submitHermesJob(callId: String, prompt: String): MobileHermesJobSubmitResponse =
        api.submitHermesJob(callId = callId, prompt = prompt, profileId = profileId)

    override suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse =
        api.getHermesJob(jobId = jobId)

    override suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse =
        api.cancelHermesJob(jobId = jobId)
}
