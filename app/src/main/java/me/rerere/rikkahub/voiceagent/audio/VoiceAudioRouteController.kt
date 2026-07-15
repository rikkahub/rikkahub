package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord

internal interface VoiceAudioRouteController {
    fun beforeCapture()
    fun configureRecorder(recorder: AudioRecord)
    fun afterCapture()
    fun close()
}

private object TelecomVoiceAudioRouteController : VoiceAudioRouteController {
    override fun beforeCapture() = Unit

    override fun configureRecorder(recorder: AudioRecord) = Unit

    override fun afterCapture() = Unit

    override fun close() = Unit
}

internal fun selectVoiceAudioRouteController(
    owner: VoiceAudioRouteOwner,
    directFactory: () -> VoiceAudioRouteController,
): VoiceAudioRouteController = when (owner) {
    VoiceAudioRouteOwner.Telecom -> TelecomVoiceAudioRouteController
    VoiceAudioRouteOwner.DirectFallback -> directFactory()
}
