package me.rerere.rikkahub.voiceagent.livekit

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.events.RoomEvent as SdkRoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal fun createLiveKitRoomSdkAdapter(context: Context): LiveKitRoomSdkAdapter {
    val automationAudio = LiveKitAutomationPcmSource()
    val injectedPcmProcessor = LiveKitInjectedPcmProcessor(automationAudio)
    return AndroidLiveKitRoomSdkAdapter(
        room = LiveKit.create(
            appContext = context,
            overrides = LiveKitOverrides(
                audioOptions = liveKitAutomationAudioOptions(injectedPcmProcessor),
            ),
        ),
        automationAudio = automationAudio,
    )
}

internal fun liveKitAutomationAudioOptions(
    injectedPcmProcessor: LiveKitInjectedPcmProcessor,
): AudioOptions =
    AudioOptions(
        audioHandler = NoAudioHandler(),
        audioProcessorOptions = AudioProcessorOptions(
            capturePostProcessor = injectedPcmProcessor,
            capturePostBypass = false,
        ),
    )

internal class AndroidLiveKitRoomSdkAdapter(
    private val room: Room,
    sdkEvents: Flow<SdkRoomEvent> = room.events.events,
    override val automationAudio: LiveKitAutomationAudioBinding =
        UnavailableLiveKitAutomationAudioBinding,
    private val remoteAudioProbeFactory: () -> LiveKitRemoteAudioProbe = {
        LiveKitRemoteAudioProbe()
    },
) : LiveKitRoomSdkAdapter {
    private val remoteAudioLock = Any()
    private val remoteAudioProbes = IdentityHashMap<RemoteAudioTrack, RemoteAudioAttachment>()
    private var remoteAudioClosed = false
    private var expectedRemoteParticipantIdentity: String? = null

    override val events: Flow<LiveKitSdkRoomEvent> = sdkEvents.mapNotNull(::toSdkEvent)

    override fun selectRemoteAudioParticipant(participantIdentity: String) {
        require(participantIdentity.isNotBlank()) {
            "Expected LiveKit remote audio participant is blank"
        }
        synchronized(remoteAudioLock) {
            check(!remoteAudioClosed) {
                "LiveKit remote audio is closed"
            }
            check(remoteAudioProbes.isEmpty()) {
                "LiveKit remote audio participant cannot change while a sink is attached"
            }
            expectedRemoteParticipantIdentity = participantIdentity
        }
    }

    override suspend fun connect(url: String, token: String) {
        room.connect(url, token)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean =
        room.localParticipant.setMicrophoneEnabled(enabled)

    override suspend fun performRpc(destination: String, method: String, payload: String): String =
        room.performRpc(Participant.Identity(destination), method, payload)

    override fun registerRpcMethod(
        method: String,
        handler: suspend (LiveKitSdkRpcInvocation) -> String,
    ) {
        room.registerRpcMethod(method) { invocation ->
            handler(
                LiveKitSdkRpcInvocation(
                    callerIdentity = invocation.callerIdentity.value,
                    payload = invocation.payload,
                ),
            )
        }
    }

    override fun unregisterRpcMethod(method: String) {
        room.unregisterRpcMethod(method)
    }

    override fun disconnect() {
        detachAllRemoteAudio()
        room.disconnect()
    }

    override fun release() {
        detachAllRemoteAudio(close = true)
        room.release()
    }

    private fun toSdkEvent(event: SdkRoomEvent): LiveKitSdkRoomEvent? = when (event) {
        is SdkRoomEvent.Connected -> LiveKitSdkRoomEvent.Connected
        is SdkRoomEvent.Reconnecting -> LiveKitSdkRoomEvent.Reconnecting
        is SdkRoomEvent.Reconnected -> LiveKitSdkRoomEvent.Reconnected
        is SdkRoomEvent.Disconnected -> {
            detachAllRemoteAudio()
            LiveKitSdkRoomEvent.Disconnected(event.error)
        }
        is SdkRoomEvent.FailedToConnect -> LiveKitSdkRoomEvent.FailedToConnect(event.error)
        is SdkRoomEvent.ParticipantDisconnected -> LiveKitSdkRoomEvent.ParticipantDisconnected(
            event.participant.identity?.value,
        )
        is SdkRoomEvent.DataReceived -> LiveKitSdkRoomEvent.DataReceived(
            participantIdentity = event.participant?.identity?.value,
            topic = event.topic,
            data = event.data,
        )
        is SdkRoomEvent.TrackSubscribed -> {
            (event.track as? RemoteAudioTrack)?.let { track ->
                attachRemoteAudio(
                    track = track,
                    participantIdentity = event.participant.identity?.value,
                    source = event.publication.source,
                )
            }
            null
        }
        is SdkRoomEvent.TrackUnsubscribed -> {
            (event.track as? RemoteAudioTrack)?.let(::detachRemoteAudio)
            null
        }
        else -> null
    }

    private fun attachRemoteAudio(
        track: RemoteAudioTrack,
        participantIdentity: String?,
        source: Track.Source,
    ) {
        synchronized(remoteAudioLock) {
            if (
                remoteAudioClosed ||
                participantIdentity != expectedRemoteParticipantIdentity ||
                source != Track.Source.MICROPHONE ||
                remoteAudioProbes.isNotEmpty()
            ) {
                return
            }
            val probe = remoteAudioProbeFactory()
            try {
                track.addSink(probe)
                remoteAudioProbes[track] = RemoteAudioAttachment(probe)
            } catch (error: Throwable) {
                probe.close()
                throw error
            }
        }
    }

    private fun detachRemoteAudio(track: RemoteAudioTrack) {
        synchronized(remoteAudioLock) {
            val attachment = remoteAudioProbes[track] ?: return
            attachment.closeProbe()
            track.removeSink(attachment.probe)
            remoteAudioProbes.remove(track)
        }
    }

    private fun detachAllRemoteAudio(close: Boolean = false) {
        val firstFailure = synchronized(remoteAudioLock) {
            if (close) remoteAudioClosed = true
            var failure: Throwable? = null
            val iterator = remoteAudioProbes.entries.iterator()
            while (iterator.hasNext()) {
                val (track, attachment) = iterator.next()
                try {
                    attachment.closeProbe()
                } catch (error: Throwable) {
                    failure = failure.withFailure(error)
                }
                try {
                    track.removeSink(attachment.probe)
                    iterator.remove()
                } catch (error: Throwable) {
                    failure = failure.withFailure(error)
                }
            }
            failure
        }
        firstFailure?.let { throw it }
    }

    private fun Throwable?.withFailure(error: Throwable): Throwable =
        this?.also { it.addSuppressed(error) } ?: error

    private class RemoteAudioAttachment(
        val probe: LiveKitRemoteAudioProbe,
    ) {
        private var closed = false

        fun closeProbe() {
            if (closed) return
            closed = true
            probe.close()
        }
    }
}
