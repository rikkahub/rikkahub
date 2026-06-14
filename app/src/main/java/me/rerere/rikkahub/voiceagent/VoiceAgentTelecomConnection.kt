package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.ParcelUuid
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.DisconnectCause

class VoiceAgentTelecomConnection(
    private val context: Context,
    private val onDisconnected: (VoiceAgentTelecomConnection) -> Unit,
) : Connection(), VoiceAgentTelecomCall {
    private var requestedBluetoothEndpointId: ParcelUuid? = null
    private var requestedLegacyBluetoothRoute = false

    override fun onDisconnect() {
        context.startService(voiceAgentCallEndIntent(context))
        disconnect(cause = DisconnectCause(DisconnectCause.LOCAL))
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }

        val candidates = availableEndpoints.map { it.toCandidate() }
        VoiceAgentLog.d(
            TAG,
            "available call endpoints=${candidates.joinToString { it.debugLabel() }}",
        )
        val selected = selectPreferredCallEndpoint(candidates) ?: run {
            requestLegacyBluetoothRouteBestEffort()
            return
        }
        val endpoint = availableEndpoints.firstOrNull { it.identifier.toString() == selected.id } ?: return
        if (!shouldRequestBluetoothCallEndpoint(
                currentEndpoint = currentCallEndpointOrNull(),
                requestedBluetoothEndpointId = requestedBluetoothEndpointId?.toString(),
                selectedBluetoothEndpointId = endpoint.identifier.toString(),
            )
        ) {
            return
        }
        requestedBluetoothEndpointId = endpoint.identifier
        requestCallEndpointChange(
            endpoint,
            context.mainExecutor,
            object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) {
                    VoiceAgentLog.d(TAG, "Bluetooth call endpoint request accepted endpoint=${endpoint.safeLabel()}")
                }

                override fun onError(error: CallEndpointException) {
                    requestedBluetoothEndpointId = null
                    VoiceAgentLog.w(
                        TAG,
                        "Bluetooth call endpoint request failed endpoint=${endpoint.safeLabel()} code=${error.code}",
                    )
                }
            },
        )
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            VoiceAgentLog.d(TAG, "call endpoint changed endpoint=${callEndpoint.safeLabel()}")
            if (callEndpoint.endpointType == CallEndpoint.TYPE_BLUETOOTH) {
                requestedBluetoothEndpointId = null
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        VoiceAgentLog.d(TAG, "call audio state changed route=${state.route} supported=${state.supportedRouteMask}")
        if (state.route == CallAudioState.ROUTE_BLUETOOTH) {
            requestedLegacyBluetoothRoute = false
        }
    }

    override fun disconnectFromApp() {
        disconnect(cause = DisconnectCause(DisconnectCause.LOCAL))
    }

    private fun disconnect(cause: DisconnectCause) {
        onDisconnected(this)
        setDisconnected(cause)
        destroy()
    }

    private fun CallEndpoint.toCandidate(): VoiceAgentCallEndpointCandidate =
        VoiceAgentCallEndpointCandidate(
            id = identifier.toString(),
            type = when (endpointType) {
                CallEndpoint.TYPE_BLUETOOTH -> VoiceAgentCallEndpointType.Bluetooth
                CallEndpoint.TYPE_EARPIECE -> VoiceAgentCallEndpointType.Earpiece
                CallEndpoint.TYPE_SPEAKER -> VoiceAgentCallEndpointType.Speaker
                CallEndpoint.TYPE_WIRED_HEADSET -> VoiceAgentCallEndpointType.WiredHeadset
                CallEndpoint.TYPE_STREAMING -> VoiceAgentCallEndpointType.Streaming
                else -> VoiceAgentCallEndpointType.Unknown
            },
            name = endpointName.toString(),
        )

    private fun CallEndpoint.toCurrentEndpoint(): VoiceAgentCurrentCallEndpoint =
        VoiceAgentCurrentCallEndpoint(
            id = identifier.toString(),
            type = when (endpointType) {
                CallEndpoint.TYPE_BLUETOOTH -> VoiceAgentCallEndpointType.Bluetooth
                CallEndpoint.TYPE_EARPIECE -> VoiceAgentCallEndpointType.Earpiece
                CallEndpoint.TYPE_SPEAKER -> VoiceAgentCallEndpointType.Speaker
                CallEndpoint.TYPE_WIRED_HEADSET -> VoiceAgentCallEndpointType.WiredHeadset
                CallEndpoint.TYPE_STREAMING -> VoiceAgentCallEndpointType.Streaming
                else -> VoiceAgentCallEndpointType.Unknown
            },
        )

    private fun currentCallEndpointOrNull(): VoiceAgentCurrentCallEndpoint? =
        runCatching { currentCallEndpoint.toCurrentEndpoint() }
            .onFailure { VoiceAgentLog.d(TAG, "current call endpoint unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()

    private fun VoiceAgentCallEndpointCandidate.debugLabel(): String =
        "${type.name}:${name.ifBlank { "unnamed" }}:$id"

    private fun CallEndpoint.safeLabel(): String =
        "${toCandidate().type.name}:${endpointName.toString().ifBlank { "unnamed" }}:${identifier}"

    @Suppress("DEPRECATION")
    private fun requestLegacyBluetoothRouteBestEffort() {
        if (requestedLegacyBluetoothRoute) {
            return
        }
        requestedLegacyBluetoothRoute = true
        runCatching {
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }.onSuccess {
            VoiceAgentLog.d(TAG, "legacy Bluetooth audio route requested")
        }.onFailure {
            requestedLegacyBluetoothRoute = false
            VoiceAgentLog.w(TAG, "legacy Bluetooth audio route request failed: ${it.toVoiceAgentLogDetail()}")
        }
    }

    private companion object {
        const val TAG = "VoiceAgentTelecomConnection"
    }
}
