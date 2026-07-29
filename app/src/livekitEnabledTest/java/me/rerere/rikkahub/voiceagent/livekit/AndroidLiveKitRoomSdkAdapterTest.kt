package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.RpcHandler
import io.livekit.android.room.participant.RpcInvocationData
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import livekit.LivekitModels
import livekit.org.webrtc.AudioTrackSink
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationMediaOwner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AndroidLiveKitRoomSdkAdapterTest {
    @Test
    fun `maps actual LiveKit room events and filters unsupported events`() = runTest {
        val room = mockk<Room>()
        val participant = mockk<RemoteParticipant>()
        val disconnectError = IllegalStateException("disconnected")
        val connectError = IllegalArgumentException("failed")
        val data = byteArrayOf(1, 2, 3)
        val roomEvents = MutableSharedFlow<RoomEvent>()
        val eventListenable = mockk<EventListenable<RoomEvent>>()
        every { participant.identity } returns Participant.Identity("agent")
        every { room.events } returns eventListenable
        every { eventListenable.events } returns roomEvents
        val sdkEvents = listOf(
            RoomEvent.Connected(room),
            RoomEvent.Reconnecting(room),
            RoomEvent.Reconnected(room),
            RoomEvent.Disconnected(room, disconnectError, DisconnectReason.CLIENT_INITIATED),
            RoomEvent.FailedToConnect(room, connectError),
            RoomEvent.ParticipantDisconnected(room, participant),
            RoomEvent.ParticipantConnected(room, participant),
            RoomEvent.DataReceived(
                room = room,
                data = data,
                participant = participant,
                topic = "voice-agent",
                encryptionType = LivekitModels.Encryption.Type.NONE,
            ),
        )

        val mappedResult = async {
            AndroidLiveKitRoomSdkAdapter(room).events.take(7).toList()
        }
        runCurrent()
        sdkEvents.forEach { roomEvents.emit(it) }
        val mapped = mappedResult.await()

        assertEquals(LiveKitSdkRoomEvent.Connected, mapped[0])
        assertEquals(LiveKitSdkRoomEvent.Reconnecting, mapped[1])
        assertEquals(LiveKitSdkRoomEvent.Reconnected, mapped[2])
        assertEquals(LiveKitSdkRoomEvent.Disconnected(disconnectError), mapped[3])
        assertEquals(LiveKitSdkRoomEvent.FailedToConnect(connectError), mapped[4])
        assertEquals(LiveKitSdkRoomEvent.ParticipantDisconnected("agent"), mapped[5])
        val received = mapped[6] as LiveKitSdkRoomEvent.DataReceived
        assertEquals("agent", received.participantIdentity)
        assertEquals("voice-agent", received.topic)
        assertArrayEquals(data, received.data)
        assertEquals(7, mapped.size)
    }

    @Test
    fun `forwards room microphone rpc registration and lifecycle operations`() = runTest {
        val room = mockk<Room>()
        val localParticipant = mockk<LocalParticipant>()
        val rpcHandler = slot<RpcHandler>()
        every { room.localParticipant } returns localParticipant
        coJustRun { room.connect("wss://livekit.example", "token") }
        coEvery { localParticipant.setMicrophoneEnabled(true) } returns true
        coEvery {
            room.performRpc(Participant.Identity("agent"), "method", "payload", any(), any())
        } returns "rpc-result"
        every { room.registerRpcMethod("method", capture(rpcHandler)) } just Runs
        every { room.unregisterRpcMethod("method") } just Runs
        every { room.disconnect() } just Runs
        every { room.release() } just Runs
        val adapter = AndroidLiveKitRoomSdkAdapter(room, flowOf<RoomEvent>())

        adapter.connect("wss://livekit.example", "token")
        assertEquals(true, adapter.setMicrophoneEnabled(true))
        assertEquals("rpc-result", adapter.performRpc("agent", "method", "payload"))
        adapter.registerRpcMethod("method") { invocation ->
            "${invocation.callerIdentity}:${invocation.payload}"
        }
        val handlerResult = rpcHandler.captured(
            RpcInvocationData(
                requestId = "request-id",
                callerIdentity = Participant.Identity("caller"),
                payload = "request-payload",
                responseTimeout = 10.seconds,
            ),
        )
        adapter.unregisterRpcMethod("method")
        adapter.disconnect()
        adapter.release()

        assertEquals("caller:request-payload", handlerResult)
        coVerify(exactly = 1) { room.connect("wss://livekit.example", "token") }
        coVerify(exactly = 1) { localParticipant.setMicrophoneEnabled(true) }
        coVerify(exactly = 1) {
            room.performRpc(Participant.Identity("agent"), "method", "payload", any(), any())
        }
        verify(exactly = 1) { room.registerRpcMethod("method", any()) }
        verify(exactly = 1) { room.unregisterRpcMethod("method") }
        verify(exactly = 1) { room.disconnect() }
        verify(exactly = 1) { room.release() }
    }

    @Test
    fun `remote audio sink follows subscribe unsubscribe disconnect and release ownership`() = runTest {
        val room = mockk<Room>()
        val participant = mockk<RemoteParticipant>()
        val publication = mockk<TrackPublication>()
        val roomEvents = MutableSharedFlow<RoomEvent>()
        val tracks = List(4) { mockk<RemoteAudioTrack>() }
        val probes = List(4) { mockk<LiveKitRemoteAudioProbe>() }
        every { participant.identity } returns Participant.Identity("agent")
        every { publication.source } returns Track.Source.MICROPHONE
        tracks.forEach { track ->
            every { track.addSink(any()) } just Runs
            every { track.removeSink(any()) } just Runs
        }
        probes.forEach { probe ->
            every { probe.close() } just Runs
        }
        every { room.disconnect() } just Runs
        every { room.release() } just Runs
        val pendingProbes = ArrayDeque(probes)
        val adapter = AndroidLiveKitRoomSdkAdapter(
            room = room,
            sdkEvents = roomEvents,
            remoteAudioProbeFactory = { pendingProbes.removeFirst() },
        )
        adapter.selectRemoteAudioParticipant("agent")
        val collection = backgroundScope.launch {
            adapter.events.collect { }
        }
        runCurrent()

        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[0], publication, participant))
        roomEvents.emit(RoomEvent.TrackUnsubscribed(room, tracks[0], publication, participant))
        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[1], publication, participant))
        roomEvents.emit(
            RoomEvent.Disconnected(
                room,
                IllegalStateException("network lost"),
                DisconnectReason.CLIENT_INITIATED,
            ),
        )
        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[2], publication, participant))
        adapter.disconnect()
        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[3], publication, participant))
        adapter.release()
        collection.cancel()

        tracks.zip(probes).forEach { (track, probe) ->
            verify(exactly = 1) { track.addSink(probe) }
            verify(exactly = 1) { track.removeSink(probe) }
            verify(exactly = 1) { probe.close() }
        }
        verify(exactly = 1) { room.disconnect() }
        verify(exactly = 1) { room.release() }
    }

    @Test
    fun `failed sink removal stays owned and blocks disconnect until retry succeeds`() = runTest {
        val room = mockk<Room>()
        val participant = mockk<RemoteParticipant>()
        val publication = mockk<TrackPublication>()
        val track = mockk<RemoteAudioTrack>()
        val probe = mockk<LiveKitRemoteAudioProbe>()
        val roomEvents = MutableSharedFlow<RoomEvent>()
        var removalAttempts = 0
        every { participant.identity } returns Participant.Identity("agent")
        every { publication.source } returns Track.Source.MICROPHONE
        every { track.addSink(probe) } just Runs
        every { track.removeSink(probe) } answers {
            removalAttempts += 1
            if (removalAttempts == 1) {
                throw IllegalStateException("remove failed once")
            }
        }
        every { probe.close() } just Runs
        every { room.disconnect() } just Runs
        val adapter = AndroidLiveKitRoomSdkAdapter(
            room = room,
            sdkEvents = roomEvents,
            remoteAudioProbeFactory = { probe },
        )
        adapter.selectRemoteAudioParticipant("agent")
        val collection = backgroundScope.launch {
            adapter.events.collect { }
        }
        runCurrent()
        roomEvents.emit(RoomEvent.TrackSubscribed(room, track, publication, participant))

        val firstFailure = runCatching { adapter.disconnect() }.exceptionOrNull()

        assertTrue(firstFailure is IllegalStateException)
        assertEquals(1, removalAttempts)
        verify(exactly = 0) { room.disconnect() }

        adapter.disconnect()
        collection.cancel()

        assertEquals(2, removalAttempts)
        verify(exactly = 1) { probe.close() }
        verify(exactly = 1) { room.disconnect() }
    }

    @Test
    fun `only expected agent microphone can drive or drain shared remote audio state`() = runTest {
        val room = mockk<Room>()
        val expectedParticipant = mockk<RemoteParticipant>()
        val irrelevantParticipant = mockk<RemoteParticipant>()
        val expectedPublication = mockk<TrackPublication>()
        val irrelevantPublication = mockk<TrackPublication>()
        val expectedTrack = mockk<RemoteAudioTrack>()
        val irrelevantTrack = mockk<RemoteAudioTrack>()
        val expectedSink = slot<AudioTrackSink>()
        val roomEvents = MutableSharedFlow<RoomEvent>()
        val recording = AdapterRecordingAudioProbe()
        every { expectedParticipant.identity } returns Participant.Identity("expected-agent")
        every { irrelevantParticipant.identity } returns Participant.Identity("other-agent")
        every { expectedPublication.source } returns Track.Source.MICROPHONE
        every { irrelevantPublication.source } returns Track.Source.MICROPHONE
        every { expectedTrack.addSink(capture(expectedSink)) } just Runs
        every { expectedTrack.removeSink(any()) } just Runs
        every { irrelevantTrack.addSink(any()) } just Runs
        every { irrelevantTrack.removeSink(any()) } just Runs
        val adapter = AndroidLiveKitRoomSdkAdapter(
            room = room,
            sdkEvents = roomEvents,
            remoteAudioProbeFactory = {
                LiveKitRemoteAudioProbe(
                    automationAudioProbe = recording,
                    monotonicMs = { 1L },
                )
            },
        )
        adapter.selectRemoteAudioParticipant("expected-agent")
        val collection = backgroundScope.launch {
            adapter.events.collect { }
        }
        runCurrent()

        roomEvents.emit(
            RoomEvent.TrackSubscribed(
                room,
                expectedTrack,
                expectedPublication,
                expectedParticipant,
            ),
        )
        roomEvents.emit(
            RoomEvent.TrackSubscribed(
                room,
                irrelevantTrack,
                irrelevantPublication,
                irrelevantParticipant,
            ),
        )
        expectedSink.captured.onData(
            ByteBuffer.wrap(byteArrayOf(1, 0)),
            16,
            48_000,
            1,
            1,
            1,
        )
        roomEvents.emit(
            RoomEvent.TrackUnsubscribed(
                room,
                irrelevantTrack,
                irrelevantPublication,
                irrelevantParticipant,
            ),
        )

        assertEquals(listOf(true), recording.nonSilentWrites)
        assertEquals(0, recording.drained)
        verify(exactly = 0) { irrelevantTrack.addSink(any()) }
        verify(exactly = 0) { irrelevantTrack.removeSink(any()) }

        expectedSink.captured.onData(
            ByteBuffer.wrap(byteArrayOf(0, 0)),
            16,
            48_000,
            1,
            1,
            2,
        )
        roomEvents.emit(
            RoomEvent.TrackUnsubscribed(
                room,
                expectedTrack,
                expectedPublication,
                expectedParticipant,
            ),
        )
        collection.cancel()

        assertEquals(1, recording.silenceConfirmations)
        assertEquals(1, recording.drained)
        verify(exactly = 1) { expectedTrack.removeSink(any()) }
    }

    private class AdapterRecordingAudioProbe : VoiceAutomationAudioProbe {
        val nonSilentWrites = mutableListOf<Boolean>()
        var silenceConfirmations = 0
        var drained = 0

        override fun onInjectionStarted(totalBytes: Long) = Unit
        override fun onInjectionChunk(byteCount: Int) = Unit
        override fun onInjectionCompleted() = Unit
        override fun onOutputQueued(byteCount: Int) = Unit
        override fun captureLiveKitMediaOwner() = VoiceAutomationMediaOwner(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) {
            nonSilentWrites += nonSilent
        }

        override fun onOutputDrained() {
            drained += 1
        }

        override fun onInterruptionStarted() = Unit

        override fun onOutputSilenceConfirmed() {
            silenceConfirmations += 1
        }
    }
}
