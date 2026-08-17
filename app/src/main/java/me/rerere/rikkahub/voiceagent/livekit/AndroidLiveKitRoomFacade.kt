package me.rerere.rikkahub.voiceagent.livekit

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import me.rerere.rikkahub.voiceagent.telemetry.VoiceLatencyTelemetryCoordinator

internal sealed interface LiveKitSdkRoomEvent {
    data object Connected : LiveKitSdkRoomEvent
    data object Reconnecting : LiveKitSdkRoomEvent
    data object Reconnected : LiveKitSdkRoomEvent
    data class Disconnected(val error: Throwable?) : LiveKitSdkRoomEvent
    data class FailedToConnect(val error: Throwable) : LiveKitSdkRoomEvent
    data class ParticipantDisconnected(val participantIdentity: String?) : LiveKitSdkRoomEvent
    data class DataReceived(
        val participantIdentity: String?,
        val topic: String?,
        val data: ByteArray,
    ) : LiveKitSdkRoomEvent
}

internal data class LiveKitSdkRpcInvocation(
    val callerIdentity: String,
    val payload: String,
)

internal interface LiveKitRoomSdkAdapter {
    val events: Flow<LiveKitSdkRoomEvent>
    val automationAudio: LiveKitAutomationAudioBinding
        get() = UnavailableLiveKitAutomationAudioBinding

    fun attachTelemetry(telemetryCoordinator: VoiceLatencyTelemetryCoordinator) = Unit

    fun selectRemoteAudioParticipant(participantIdentity: String)
    suspend fun connect(url: String, token: String)
    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean
    suspend fun performRpc(destination: String, method: String, payload: String): String
    fun registerRpcMethod(method: String, handler: suspend (LiveKitSdkRpcInvocation) -> String)
    fun unregisterRpcMethod(method: String)
    fun disconnect()
    fun release()
}

internal class AndroidLiveKitRoomFacade(
    private val sdk: LiveKitRoomSdkAdapter,
) : LiveKitRoomFacade {
    constructor(context: Context) : this(createLiveKitRoomSdkAdapter(context))

    override val events: Flow<LiveKitRoomEvent> = sdk.events.mapNotNull(::toFacadeEvent)
    override val automationAudio: LiveKitAutomationAudioBinding = sdk.automationAudio

    override fun attachTelemetry(telemetryCoordinator: VoiceLatencyTelemetryCoordinator) {
        sdk.attachTelemetry(telemetryCoordinator)
    }

    override fun selectRemoteAudioParticipant(participantIdentity: String) {
        sdk.selectRemoteAudioParticipant(participantIdentity)
    }

    override suspend fun connect(url: String, token: String) {
        sdk.connect(url, token)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean =
        sdk.setMicrophoneEnabled(enabled)

    override suspend fun performRpc(destination: String, method: String, payload: String): String =
        sdk.performRpc(destination, method, payload)

    override fun registerRpcMethod(
        method: String,
        handler: suspend (LiveKitRpcInvocation) -> String,
    ) {
        sdk.registerRpcMethod(method) { invocation ->
            handler(LiveKitRpcInvocation(invocation.callerIdentity, invocation.payload))
        }
    }

    override fun unregisterRpcMethod(method: String) {
        sdk.unregisterRpcMethod(method)
    }

    override fun disconnect() {
        sdk.disconnect()
    }

    override fun close() {
        sdk.release()
    }

    private fun toFacadeEvent(event: LiveKitSdkRoomEvent): LiveKitRoomEvent? = when (event) {
        LiveKitSdkRoomEvent.Connected -> LiveKitRoomEvent.Connected
        LiveKitSdkRoomEvent.Reconnecting -> LiveKitRoomEvent.Reconnecting
        LiveKitSdkRoomEvent.Reconnected -> LiveKitRoomEvent.Reconnected
        is LiveKitSdkRoomEvent.Disconnected -> LiveKitRoomEvent.Disconnected(event.error)
        is LiveKitSdkRoomEvent.FailedToConnect -> LiveKitRoomEvent.Failed(event.error)
        is LiveKitSdkRoomEvent.ParticipantDisconnected -> LiveKitRoomEvent.ParticipantDisconnected(
            event.participantIdentity ?: return null,
        )
        is LiveKitSdkRoomEvent.DataReceived -> LiveKitRoomEvent.Data(
            participantIdentity = event.participantIdentity ?: return null,
            topic = event.topic ?: return null,
            payload = event.data.decodeToString(),
        )
    }

}
