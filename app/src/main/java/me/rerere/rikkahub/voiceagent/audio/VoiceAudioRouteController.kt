package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord

internal interface VoiceAudioCaptureRouteLease {
    fun configureRecorder(recorder: AudioRecord)
    fun retire()
}

internal interface VoiceAudioRouteController {
    fun acquireCapture(): VoiceAudioCaptureRouteLease
    fun close()
}

private object TelecomVoiceAudioRouteController : VoiceAudioRouteController {
    override fun acquireCapture(): VoiceAudioCaptureRouteLease = TelecomVoiceAudioCaptureRouteLease

    override fun close() = Unit
}

private object TelecomVoiceAudioCaptureRouteLease : VoiceAudioCaptureRouteLease {
    override fun configureRecorder(recorder: AudioRecord) = Unit

    override fun retire() = Unit
}

internal fun selectVoiceAudioRouteController(
    owner: VoiceAudioRouteOwner,
    directFactory: () -> VoiceAudioRouteController,
): VoiceAudioRouteController = when (owner) {
    VoiceAudioRouteOwner.Telecom -> TelecomVoiceAudioRouteController
    VoiceAudioRouteOwner.DirectFallback -> directFactory()
}
