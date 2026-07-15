package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackEventOwnerTest {
    @Test
    fun `forwards events to installed handler`() {
        val events = mutableListOf<VoicePlaybackEvent>()
        val owner = VoicePlaybackEventOwner()
        owner.setHandler(events::add)

        owner.notify(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))

        assertEquals(listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L))), events)
    }

    @Test
    fun `release retains handler for terminal event then clears it`() {
        val events = mutableListOf<VoicePlaybackEvent>()
        val owner = VoicePlaybackEventOwner()
        owner.setHandler(events::add)

        owner.releasePlayback { onPlaybackEventsDrained ->
            owner.notify(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))
            onPlaybackEventsDrained()
        }
        owner.notify(VoicePlaybackEvent.Active(PlaybackEpoch(2L)))

        assertEquals(listOf(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))), events)
    }

    @Test
    fun `release keeps handler until playback event completion runs`() {
        val events = mutableListOf<VoicePlaybackEvent>()
        val owner = VoicePlaybackEventOwner()
        owner.setHandler(events::add)
        lateinit var completePlaybackEvents: () -> Unit

        owner.releasePlayback { onPlaybackEventsDrained ->
            completePlaybackEvents = onPlaybackEventsDrained
        }
        owner.notify(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))
        completePlaybackEvents()
        owner.notify(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))

        assertEquals(listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L))), events)
    }
}
