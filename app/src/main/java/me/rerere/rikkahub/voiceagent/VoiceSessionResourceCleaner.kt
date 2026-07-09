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

    private fun runCleanupSequence(
        closeGemini: Boolean,
        prepare: () -> Unit = {},
        isAutomaticReconnectCurrentUnderCleanupLock: () -> Boolean = { true },
    ): Boolean {
        if (!isAutomaticReconnectCurrentUnderCleanupLock()) return false
        detachHermesBridge()
        prepare()
        if (!isAutomaticReconnectCurrentUnderCleanupLock()) return false
        invalidateAudioSessions()
        if (!isAutomaticReconnectCurrentUnderCleanupLock()) return false
        audio.stopCapture()
        if (!isAutomaticReconnectCurrentUnderCleanupLock()) return false
        audio.suppressPlayback()
        if (!isAutomaticReconnectCurrentUnderCleanupLock()) return false
        if (closeGemini) {
            gemini.close()
        }
        return isAutomaticReconnectCurrentUnderCleanupLock()
    }

    fun cleanupForReconnect(closeGemini: Boolean) {
        synchronized(cleanupLock) {
            runCleanupSequence(
                closeGemini = closeGemini,
                prepare = { coordinator.prepareFor(SessionTransition.Reconnect) },
            )
        }
    }

    fun cleanupForAutomaticReconnect(
        closeGemini: Boolean,
        isAutomaticReconnectCurrentUnderCleanupLock: () -> Boolean = { true },
    ): Boolean = synchronized(cleanupLock) {
        runCleanupSequence(
            closeGemini = closeGemini,
            isAutomaticReconnectCurrentUnderCleanupLock = isAutomaticReconnectCurrentUnderCleanupLock,
        )
    }

    fun cleanupForFailure(closeGemini: Boolean) {
        synchronized(cleanupLock) {
            runCleanupSequence(
                closeGemini = closeGemini,
                prepare = { coordinator.prepareFor(SessionTransition.SessionEnd) },
            )
        }
    }

    fun cleanupForEnd(closeGemini: Boolean) {
        synchronized(cleanupLock) {
            runCleanupSequence(
                closeGemini = closeGemini,
                prepare = { coordinator.prepareFor(SessionTransition.SessionEnd) },
            )
        }
    }
}
