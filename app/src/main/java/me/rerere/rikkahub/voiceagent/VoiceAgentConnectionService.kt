package me.rerere.rikkahub.voiceagent

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import org.koin.android.ext.android.inject

class VoiceAgentConnectionService : ConnectionService() {
    private val telecomCallRegistry: VoiceAgentTelecomCallRegistry by inject()

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val attemptId = request?.address.voiceAgentTelecomAttemptIdOrNull()
        val connection = VoiceAgentTelecomConnection(
            context = applicationContext,
            onDisconnected = telecomCallRegistry::clear,
        ).apply {
            setAudioModeIsVoip(true)
            setInitializing()
        }
        if (attemptId != null && telecomCallRegistry.activate(attemptId, connection)) {
            connection.setActive()
        } else if (attemptId == null) {
            connection.disconnectFromApp()
        }
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        val detail = "Android Telecom rejected Voice Agent call"
        request?.address.voiceAgentTelecomAttemptIdOrNull()?.let { attemptId ->
            telecomCallRegistry.fail(
                attemptId,
                VoiceAgentTelecomFailure(
                    diagnosticName = "telecom_outgoing_failed",
                    detail = detail,
                ),
            )
        }
    }
}
