package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.voiceAgentLaunchLabel

internal enum class VoiceAgentLaunchLocation {
    TopBar,
    Options,
}

internal data class VoiceAgentLaunchAction(
    val label: String,
    val transport: VoiceAgentTransport,
    val location: VoiceAgentLaunchLocation,
) {
    val inOptions: Boolean
        get() = location == VoiceAgentLaunchLocation.Options
}

internal fun launchActions(experimentEnabled: Boolean): List<VoiceAgentLaunchAction> = buildList {
    add(
        VoiceAgentLaunchAction(
            label = voiceAgentLaunchLabel(),
            transport = VoiceAgentTransport.DirectGemini,
            location = VoiceAgentLaunchLocation.TopBar,
        )
    )
    if (experimentEnabled) {
        add(
            VoiceAgentLaunchAction(
                label = "Voice Agent via LiveKit (Experimental)",
                transport = VoiceAgentTransport.LiveKitExperimental,
                location = VoiceAgentLaunchLocation.Options,
            )
        )
    }
}

internal fun voiceAgentScreen(
    conversationId: String,
    action: VoiceAgentLaunchAction,
): Screen.VoiceAgent = Screen.VoiceAgent(
    conversationId = conversationId,
    transportWireName = action.transport.wireName,
)
