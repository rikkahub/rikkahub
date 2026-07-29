package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource

internal sealed interface LiveKitRoomEvent {
    data object Connected : LiveKitRoomEvent
    data object Reconnecting : LiveKitRoomEvent
    data object Reconnected : LiveKitRoomEvent
    data class Disconnected(val error: Throwable? = null) : LiveKitRoomEvent
    data class Failed(val error: Throwable) : LiveKitRoomEvent
    data class ParticipantDisconnected(val participantIdentity: String) : LiveKitRoomEvent
    data class Data(
        val participantIdentity: String,
        val topic: String,
        val payload: String,
    ) : LiveKitRoomEvent
}

internal data class LiveKitRpcInvocation(
    val callerIdentity: String,
    val payload: String,
)

internal interface LiveKitAutomationAudioBinding {
    fun activate(
        runHash: String,
        captureSource: VoiceCaptureSource,
        scope: CoroutineScope,
    ): AutoCloseable
}

internal object UnavailableLiveKitAutomationAudioBinding : LiveKitAutomationAudioBinding {
    override fun activate(
        runHash: String,
        captureSource: VoiceCaptureSource,
        scope: CoroutineScope,
    ): AutoCloseable =
        error("LiveKit automation audio is unavailable")
}

internal interface LiveKitRoomFacade {
    val events: Flow<LiveKitRoomEvent>
    val automationAudio: LiveKitAutomationAudioBinding
        get() = UnavailableLiveKitAutomationAudioBinding

    fun selectRemoteAudioParticipant(participantIdentity: String) = Unit
    suspend fun connect(url: String, token: String)
    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean
    suspend fun performRpc(destination: String, method: String, payload: String): String
    fun registerRpcMethod(method: String, handler: suspend (LiveKitRpcInvocation) -> String)
    fun unregisterRpcMethod(method: String)
    fun disconnect()
    fun close()
}
