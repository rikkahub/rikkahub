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
    private val cleanupLock = Any()

    private fun invalidateAudioSessions() {
        gemini.invalidateOutboundSession()
        audio.invalidatePlaybackSession()
    }

    private fun detachHermesBridge() {
        hermesBridgeProvider()?.let(coordinator::detachHermesBridge)
        clearHermesBridge()
    }

    fun cleanupForReconnect(closeGemini: Boolean) = synchronized(cleanupLock) {
        detachHermesBridge()
        coordinator.prepareForReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    fun cleanupForAutomaticReconnect(
        closeGemini: Boolean,
        shouldContinue: () -> Boolean = { true },
    ): Boolean = synchronized(cleanupLock) {
        if (!shouldContinue()) return false
        detachHermesBridge()
        if (!shouldContinue()) return false
        invalidateAudioSessions()
        if (!shouldContinue()) return false
        audio.stopCapture()
        if (!shouldContinue()) return false
        audio.suppressPlayback()
        if (!shouldContinue()) return false
        if (closeGemini) {
            gemini.close()
        }
        return shouldContinue()
    }

    fun cleanupForFailure(closeGemini: Boolean) = synchronized(cleanupLock) {
        detachHermesBridge()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    fun cleanupForEnd(closeGemini: Boolean) = synchronized(cleanupLock) {
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
