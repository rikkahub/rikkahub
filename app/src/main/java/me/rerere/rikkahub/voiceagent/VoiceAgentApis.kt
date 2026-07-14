package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesJobSubmitResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileVoiceSessionResponse
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi

interface VoiceSessionApi {
    suspend fun createSession(modelId: String): MobileVoiceSessionResponse
}

class HermesVoiceSessionApi(
    private val api: HermesVoiceApi,
) : VoiceSessionApi {
    override suspend fun createSession(modelId: String): MobileVoiceSessionResponse =
        api.createSession(modelId = modelId)
}

interface VoiceToolApi {
    suspend fun submitHermesJob(callId: String, prompt: String): MobileHermesJobSubmitResponse
    suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse
    suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse
}

class HermesVoiceToolApi(
    private val api: HermesVoiceApi,
    private val profileId: String? = null,
) : VoiceToolApi {
    override suspend fun submitHermesJob(callId: String, prompt: String): MobileHermesJobSubmitResponse =
        api.submitHermesJob(callId = callId, prompt = prompt, profileId = profileId)

    override suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse =
        api.getHermesJob(jobId = jobId)

    override suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse =
        api.cancelHermesJob(jobId = jobId)
}
