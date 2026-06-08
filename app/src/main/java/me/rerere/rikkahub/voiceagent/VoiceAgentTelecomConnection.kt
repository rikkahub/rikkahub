package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.telecom.Connection
import android.telecom.DisconnectCause

class VoiceAgentTelecomConnection(
    private val context: Context,
    private val onDisconnected: (VoiceAgentTelecomConnection) -> Unit,
) : Connection(), VoiceAgentTelecomCall {
    override fun onDisconnect() {
        context.startService(voiceAgentCallEndIntent(context))
        disconnect(cause = DisconnectCause(DisconnectCause.LOCAL))
    }

    override fun disconnectFromApp() {
        disconnect(cause = DisconnectCause(DisconnectCause.LOCAL))
    }

    private fun disconnect(cause: DisconnectCause) {
        onDisconnected(this)
        setDisconnected(cause)
        destroy()
    }
}
