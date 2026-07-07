package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Gates proactive Hermes announcements (completions, failures, still-working
 * updates, replays) so they are spoken at a conversation pause instead of the
 * moment a job finishes. Queued acknowledgements are tool responses and must
 * not go through this gate.
 */
class HermesAnnouncementScheduler(
    private val quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS,
    private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
    private val pollTickMs: Long = DEFAULT_POLL_TICK_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val recordDiagnostic: (String, String) -> Unit = { _, _ -> },
) {
    private val slotMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val bridgeAvailable = AtomicBoolean(false)
    private val assistantAudioActive = AtomicBoolean(false)
    private val lastInputDeltaAtMs = AtomicLong(NEVER)
    private val generationCompleteSinceLastSend = AtomicBoolean(true)
    private val hasSentAnnouncement = AtomicBoolean(false)

    fun onBridgeAvailable(available: Boolean) {
        bridgeAvailable.set(available)
    }

    fun onAssistantAudioActive(active: Boolean) {
        assistantAudioActive.set(active)
    }

    fun onInputTranscriptDelta() {
        lastInputDeltaAtMs.set(nowMs())
    }

    fun onGenerationComplete() {
        generationCompleteSinceLastSend.set(true)
    }

    fun close() {
        closed.set(true)
    }

    suspend fun <T> withAnnouncementSlot(label: String, send: suspend () -> T): T? {
        if (closed.get()) return null
        return slotMutex.withLock {
            if (closed.get()) return@withLock null
            var waitedMs = 0L
            while (!closed.get() && !isReady() && waitedMs < maxHoldMs) {
                delayFn(pollTickMs)
                waitedMs += pollTickMs
            }
            if (closed.get()) return@withLock null
            if (waitedMs >= maxHoldMs) {
                safeRecordDiagnostic("hermes_announcement_released_at_deadline", label)
            }
            val result = send()
            hasSentAnnouncement.set(true)
            generationCompleteSinceLastSend.set(false)
            result
        }
    }

    private fun isReady(): Boolean {
        if (!bridgeAvailable.get()) return false
        if (assistantAudioActive.get()) return false
        val lastDelta = lastInputDeltaAtMs.get()
        if (lastDelta != NEVER && nowMs() - lastDelta < quietWindowMs) return false
        if (hasSentAnnouncement.get() && !generationCompleteSinceLastSend.get()) return false
        return true
    }

    private fun safeRecordDiagnostic(name: String, detail: String) {
        runCatching { recordDiagnostic(name, detail) }
    }

    companion object {
        const val DEFAULT_QUIET_WINDOW_MS = 2_000L
        const val DEFAULT_MAX_HOLD_MS = 15_000L
        const val DEFAULT_POLL_TICK_MS = 200L
        private const val NEVER = Long.MIN_VALUE
    }
}
