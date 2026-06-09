package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioFocusPolicyTest {
    @Test
    fun `failed focus request is recoverable for Telecom managed voice call`() {
        assertFalse(
            VoiceAudioFocusPolicy.isRequestFailureFatal(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        )
    }

    @Test
    fun `delayed focus request remains fatal`() {
        assertTrue(
            VoiceAudioFocusPolicy.isRequestFailureFatal(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        )
    }

    @Test
    fun `transient focus loss is recoverable for Telecom managed voice call`() {
        assertFalse(
            VoiceAudioFocusPolicy.isFocusChangeFatal(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
    }

    @Test
    fun `permanent focus loss remains fatal`() {
        assertTrue(
            VoiceAudioFocusPolicy.isFocusChangeFatal(AudioManager.AUDIOFOCUS_LOSS)
        )
    }
}
