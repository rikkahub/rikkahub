package me.rerere.rikkahub.voiceagent

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials

data class VoiceAgentLaunchConfig(
    val hermesVoiceBaseUrl: String,
    val credentials: HermesVoiceCredentials,
    val voiceModelId: String,
    val assistantName: String,
    val assistantPrompt: String,
)

sealed interface VoiceAgentConfigResult {
    data class Available(val config: VoiceAgentLaunchConfig) : VoiceAgentConfigResult
    data class Unavailable(val message: String) : VoiceAgentConfigResult
}

class VoiceAgentConfigResolver(
    private val defaultVoiceModelId: String = DEFAULT_VOICE_MODEL_ID,
    private val baseUrlOverride: String = BuildConfig.VOICE_AGENT_BASE_URL_OVERRIDE,
) {
    fun resolve(settings: Settings, conversation: Conversation): VoiceAgentConfigResult {
        val assistant = settings.assistants.find { it.id == conversation.assistantId }
            ?: return VoiceAgentConfigResult.Unavailable("Voice Agent cannot find this chat's assistant.")
        val model = settings.findConversationChatModel(assistant)
            ?: return VoiceAgentConfigResult.Unavailable("Voice Agent cannot find the current chat model.")
        val provider = model.findProvider(settings.providers)
            ?: return VoiceAgentConfigResult.Unavailable("Voice Agent cannot find the current chat provider.")
        if (provider !is ProviderSetting.OpenAI) {
            return VoiceAgentConfigResult.Unavailable(
                "Voice Agent needs an OpenAI-compatible RMS Hermes provider."
            )
        }
        if (provider.apiKey.isBlank()) {
            return VoiceAgentConfigResult.Unavailable("Voice Agent needs a Hermes device API key on the current provider.")
        }

        val headers = assistant.customHeaders + model.customHeaders
        val hermesVoiceBaseUrl = (
            headers.valueFor(HERMES_VOICE_BASE_URL_HEADER)
                ?: baseUrlOverride.takeIf { it.isNotBlank() }
                ?: provider.baseUrl
            ).toHermesVoiceBaseUrl()
        if (hermesVoiceBaseUrl.isBlank()) {
            return VoiceAgentConfigResult.Unavailable("Voice Agent needs a Hermes origin on the current provider.")
        }

        return VoiceAgentConfigResult.Available(
            VoiceAgentLaunchConfig(
                hermesVoiceBaseUrl = hermesVoiceBaseUrl,
                credentials = HermesVoiceCredentials(deviceApiKey = provider.apiKey),
                voiceModelId = headers.valueFor(VOICE_AGENT_MODEL_ID_HEADER) ?: defaultVoiceModelId,
                assistantName = assistant.name.ifBlank { "Default Assistant" },
                assistantPrompt = conversation.customSystemPrompt ?: assistant.systemPrompt,
            )
        )
    }

    private fun Settings.findConversationChatModel(assistant: Assistant): Model? {
        return findModelById(assistant.chatModelId ?: chatModelId)
    }

    private fun List<CustomHeader>.valueFor(name: String): String? {
        return lastOrNull { it.name.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.toHermesVoiceBaseUrl(): String {
        return trim()
            .trimEnd('/')
            .removeSuffix("/api/mobile")
            .removeSuffix("/api/openai/v1")
            .removeSuffix("/openai/v1")
            .removeSuffix("/v1")
    }

    companion object {
        const val DEFAULT_VOICE_MODEL_ID = "gemini-flash"
        const val HERMES_VOICE_BASE_URL_HEADER = "X-Hermes-Voice-Base-Url"
        const val VOICE_AGENT_MODEL_ID_HEADER = "X-Voice-Agent-Model-Id"
    }
}
