package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioManager

object VoiceAudioFocusPolicy {
    fun isRequestFailureFatal(result: Int): Boolean =
        result != AudioManager.AUDIOFOCUS_REQUEST_FAILED

    fun isFocusChangeFatal(focusChange: Int): Boolean =
        focusChange == AudioManager.AUDIOFOCUS_LOSS
}
