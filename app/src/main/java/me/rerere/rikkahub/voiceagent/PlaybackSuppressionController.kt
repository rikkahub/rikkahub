package me.rerere.rikkahub.voiceagent

/**
 * Owns the playback-suppression flags and assistant-audio-active state. Suppression
 * is set on user/Gemini interruption and cleared when a new turn starts; audio-active
 * changes are forwarded to the announcement pipeline via the constructor callback.
 */
internal class PlaybackSuppressionController(
    private val onAssistantAudioActiveChanged: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var suppressed = false
    private var suppressedByGeminiInterruption = false

    fun isSuppressed(): Boolean = synchronized(lock) { suppressed }

    fun suppress() {
        setAssistantAudioActive(false)
        synchronized(lock) { suppressed = true }
    }

    fun markInterruptedByGemini() {
        synchronized(lock) { suppressedByGeminiInterruption = true }
    }

    fun clearForNewTurn() {
        synchronized(lock) {
            suppressed = false
            suppressedByGeminiInterruption = false
        }
        setAssistantAudioActive(false)
    }

    fun clearSuppressionOnly() {
        synchronized(lock) { suppressed = false }
    }

    fun clearForAssistantTurnAfterInterruption(): Boolean = synchronized(lock) {
        if (suppressed && suppressedByGeminiInterruption) {
            suppressed = false
            suppressedByGeminiInterruption = false
            true
        } else {
            suppressedByGeminiInterruption = false
            false
        }
    }

    fun setAssistantAudioActive(active: Boolean) {
        onAssistantAudioActiveChanged(active)
    }

    /**
     * The queued-audio decision: atomically re-check suppression and staleness and
     * mark assistant audio active when playback may proceed. Returns null to proceed,
     * or the skip-diagnostic name.
     */
    fun tryActivatePlayback(isStale: () -> Boolean): String? = synchronized(lock) {
        when {
            suppressed -> "output_audio_state_suppressed_after_interruption"
            isStale() -> "stale_output_audio_state_suppressed"
            else -> {
                setAssistantAudioActive(true)
                null
            }
        }
    }
}
