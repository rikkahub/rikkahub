package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.hermes.HermesSessionBridge

internal class VoiceSessionResourceCleaner(
    private val coordinator: VoiceAgentCoordinator,
    private val gemini: GeminiLiveVoiceClient,
    private val audio: VoiceAudioEngine,
    private val hermesBridgeProvider: () -> HermesSessionBridge?,
    private val clearHermesBridge: () -> Unit,
) {
    fun invalidateAudioSessions() {
        gemini.invalidateOutboundSession()
        audio.invalidatePlaybackSession()
    }

    fun detachHermesBridge() {
        hermesBridgeProvider()?.let(coordinator::detachHermesBridge)
        clearHermesBridge()
    }

    fun cleanupForReconnect(closeGemini: Boolean) {
        detachHermesBridge()
        coordinator.prepareForReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    fun cleanupForAutomaticReconnect(closeGemini: Boolean) {
        detachHermesBridge()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    fun cleanupForFailure(closeGemini: Boolean) {
        detachHermesBridge()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    fun cleanupForEnd(closeGemini: Boolean) {
        detachHermesBridge()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }
}
