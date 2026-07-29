package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLiveKitRoomFacadeTest {
    @Test
    fun `SDK-shaped room events map to facade events and incomplete payloads are ignored`() = runTest {
        val sdk = FakeLiveKitRoomSdkAdapter()
        val facade = AndroidLiveKitRoomFacade(sdk)
        val disconnectError = IllegalStateException("disconnected")
        val connectError = IllegalArgumentException("connect failed")
        val collected = async { facade.events.take(7).toList() }
        runCurrent()

        sdk.emit(LiveKitSdkRoomEvent.Connected)
        sdk.emit(LiveKitSdkRoomEvent.Reconnecting)
        sdk.emit(LiveKitSdkRoomEvent.Reconnected)
        sdk.emit(LiveKitSdkRoomEvent.Disconnected(disconnectError))
        sdk.emit(LiveKitSdkRoomEvent.FailedToConnect(connectError))
        sdk.emit(LiveKitSdkRoomEvent.ParticipantDisconnected("agent"))
        sdk.emit(LiveKitSdkRoomEvent.ParticipantDisconnected(null))
        sdk.emit(LiveKitSdkRoomEvent.DataReceived("agent", "voice.ready.v1", "ready".encodeToByteArray()))

        val events = collected.await()
        assertSame(LiveKitRoomEvent.Connected, events[0])
        assertSame(LiveKitRoomEvent.Reconnecting, events[1])
        assertSame(LiveKitRoomEvent.Reconnected, events[2])
        assertSame(disconnectError, (events[3] as LiveKitRoomEvent.Disconnected).error)
        assertSame(connectError, (events[4] as LiveKitRoomEvent.Failed).error)
        assertEquals(LiveKitRoomEvent.ParticipantDisconnected("agent"), events[5])
        assertEquals(LiveKitRoomEvent.Data("agent", "voice.ready.v1", "ready"), events[6])
    }

    @Test
    fun `facade forwards room resources and translates RPC invocations`() = runTest {
        val sdk = FakeLiveKitRoomSdkAdapter()
        val facade = AndroidLiveKitRoomFacade(sdk)
        var invocation: LiveKitRpcInvocation? = null

        assertSame(sdk.automationAudio, facade.automationAudio)
        facade.selectRemoteAudioParticipant("agent")
        facade.connect("wss://voice.test", "token")
        assertTrue(facade.setMicrophoneEnabled(false))
        assertEquals("rpc-result", facade.performRpc("agent", "interrupt", "payload"))
        facade.registerRpcMethod("tool") {
            invocation = it
            "handler-result"
        }
        assertEquals(
            "handler-result",
            sdk.invoke("tool", LiveKitSdkRpcInvocation("caller", "arguments")),
        )
        facade.unregisterRpcMethod("tool")
        facade.disconnect()
        facade.close()

        assertEquals(
            listOf(
                "remote-audio:agent",
                "connect:wss://voice.test:token",
                "microphone:false",
                "rpc:agent:interrupt:payload",
                "register:tool",
                "unregister:tool",
                "disconnect",
                "release",
            ),
            sdk.operations,
        )
        assertEquals(LiveKitRpcInvocation("caller", "arguments"), invocation)
    }
}

private class FakeLiveKitRoomSdkAdapter : LiveKitRoomSdkAdapter {
    private val mutableEvents = MutableSharedFlow<LiveKitSdkRoomEvent>(extraBufferCapacity = 16)
    override val events: Flow<LiveKitSdkRoomEvent> = mutableEvents
    val operations = mutableListOf<String>()
    private val handlers = mutableMapOf<String, suspend (LiveKitSdkRpcInvocation) -> String>()
    override val automationAudio = FakeLiveKitAutomationAudioBinding()

    suspend fun emit(event: LiveKitSdkRoomEvent) {
        mutableEvents.emit(event)
    }

    suspend fun invoke(method: String, invocation: LiveKitSdkRpcInvocation): String =
        requireNotNull(handlers[method])(invocation)

    override fun selectRemoteAudioParticipant(participantIdentity: String) {
        operations += "remote-audio:$participantIdentity"
    }

    override suspend fun connect(url: String, token: String) {
        operations += "connect:$url:$token"
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        operations += "microphone:$enabled"
        return true
    }

    override suspend fun performRpc(destination: String, method: String, payload: String): String {
        operations += "rpc:$destination:$method:$payload"
        return "rpc-result"
    }

    override fun registerRpcMethod(
        method: String,
        handler: suspend (LiveKitSdkRpcInvocation) -> String,
    ) {
        operations += "register:$method"
        handlers[method] = handler
    }

    override fun unregisterRpcMethod(method: String) {
        operations += "unregister:$method"
        handlers.remove(method)
    }

    override fun disconnect() {
        operations += "disconnect"
    }

    override fun release() {
        operations += "release"
    }
}

private class FakeLiveKitAutomationAudioBinding : LiveKitAutomationAudioBinding {
    override fun activate(
        runHash: String,
        captureSource: me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource,
        scope: kotlinx.coroutines.CoroutineScope,
    ): AutoCloseable = AutoCloseable { }

}
