package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallServicePolicyTest {
    @Test
    fun `retained Telecom session with errored no-progress reconnect preserves Degraded status`() {
        assertFalse(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.Telecom,
                current = VoiceCallStatus.Degraded("Existing connection failed"),
            ),
        )
    }

    @Test
    fun `non-degraded Telecom start publishes BackgroundCapable status`() {
        assertTrue(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.Telecom,
                current = VoiceCallStatus.ForegroundStarting,
            ),
        )
    }

    @Test
    fun `DirectFallback never publishes BackgroundCapable status`() {
        assertFalse(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.DirectFallback,
                current = VoiceCallStatus.ForegroundStarting,
            ),
        )
        assertFalse(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.DirectFallback,
                current = VoiceCallStatus.Degraded("Telecom unavailable"),
            ),
        )
    }
}
