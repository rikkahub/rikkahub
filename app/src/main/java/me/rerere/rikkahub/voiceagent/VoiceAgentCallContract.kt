package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.content.Intent

object VoiceAgentCallContract {
    const val ACTION_START = "me.rerere.rikkahub.voiceagent.action.START"
    const val ACTION_END = "me.rerere.rikkahub.voiceagent.action.END"
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_ENABLE_VOICE_E2E_ARTIFACTS = "enableVoiceE2EArtifacts"
    const val EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID = "voiceAgentConversationId"
    const val NOTIFICATION_ID = 2401
}

fun voiceAgentCallStartIntent(
    context: Context,
    conversationId: String,
    enableVoiceE2EArtifacts: Boolean = false,
): Intent =
    Intent(context, VoiceAgentCallService::class.java)
        .setAction(VoiceAgentCallContract.ACTION_START)
        .putExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID, conversationId)
        .putExtra(VoiceAgentCallContract.EXTRA_ENABLE_VOICE_E2E_ARTIFACTS, enableVoiceE2EArtifacts)

fun voiceAgentCallEndIntent(context: Context): Intent =
    Intent(context, VoiceAgentCallService::class.java)
        .setAction(VoiceAgentCallContract.ACTION_END)
