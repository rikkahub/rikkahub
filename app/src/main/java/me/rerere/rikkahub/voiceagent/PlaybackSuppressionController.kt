package me.rerere.rikkahub.voiceagent

/**
 * Owns the playback-suppression flags. Suppression is set on user/Gemini interruption
 * and cleared when a new turn starts. Physical playback state is owned by the audio
 * engine and reported independently through [me.rerere.rikkahub.voiceagent.audio.VoicePlaybackEvent].
 */
internal class PlaybackSuppressionController {
    private val lock = Any()
    private var suppressed = false
    private var suppressedByGeminiInterruption = false

    fun isSuppressed(): Boolean = synchronized(lock) { suppressed }

    fun suppress() {
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

    /**
     * The queued-audio decision: atomically re-check suppression and staleness.
     * Returns null to proceed, or the skip-diagnostic name.
     */
    fun tryActivatePlayback(isStale: () -> Boolean): String? = synchronized(lock) {
        when {
            suppressed -> "output_audio_state_suppressed_after_interruption"
            isStale() -> "stale_output_audio_state_suppressed"
            else -> null
        }
    }
}
