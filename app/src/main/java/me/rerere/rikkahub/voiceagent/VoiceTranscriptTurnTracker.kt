package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptStatus
import me.rerere.rikkahub.voiceagent.telemetry.voiceTextMetadata
import me.rerere.rikkahub.voiceagent.telemetry.voiceTextPayload

internal enum class TranscriptSpeaker { User, Assistant }

/**
 * Single owner of transcript-turn state: buffers, turn ids, active speaker, and the
 * final-transcript telemetry dedup. Before this class the output-turn fields were
 * guarded by two different locks depending on call path; every mutation now happens
 * under the one internal lock, and playback-suppression status is an explicit input.
 */
internal class VoiceTranscriptTurnTracker(
    private val transcriptPersister: VoiceTranscriptPersister,
    private val sessionId: String,
    private val persist: (transform: (Conversation) -> Conversation, onPersisted: () -> Unit) -> Unit,
    private val recordEvent: (name: String, attributes: Map<String, Any?>) -> Unit,
    private val recordDiagnostic: (name: String, detail: String) -> Unit,
) {
    /**
     * Each mutator holds this lock across mutation AND the persist enqueue, so the
     * persistence queue's FIFO order matches lock-acquisition order — a later-state
     * transform can never be applied before an earlier one. Do not split these into
     * separate lock holds.
     */
    private val lock = Any()
    private var activeSpeaker: TranscriptSpeaker? = null
    private var inputTurnTranscript = ""
    private var outputTurnTranscript = ""
    private var outputTurnStatus = VoiceTranscriptStatus.Partial
    private var turnSequence = 0L
    private var inputTurnId = ""
    private var outputTurnId = ""
    private val finalTelemetryKeys = mutableSetOf<String>()

    fun appendUserDelta(text: String): String = synchronized(lock) {
        if (activeSpeaker != TranscriptSpeaker.User) {
            inputTurnTranscript = ""
            inputTurnId = nextTurnId(TranscriptSpeaker.User)
        }
        activeSpeaker = TranscriptSpeaker.User
        inputTurnTranscript += text
        val transcript = inputTurnTranscript
        val turnId = inputTurnId
        recordDiagnostic("input_transcript_delta", "turnId=$turnId, chars=${text.length}")
        recordEvent(
            "voicelab.mobile.transcript.input_delta",
            mapOf("turnId" to turnId) + voiceTextMetadata(key = "text", text = text),
        )
        persistUserLocked(transcript = transcript, turnId = turnId, status = VoiceTranscriptStatus.Partial)
        transcript
    }

    fun appendAssistantDelta(text: String, suppressed: Boolean): String = synchronized(lock) {
        if (activeSpeaker != TranscriptSpeaker.Assistant) {
            outputTurnTranscript = ""
            outputTurnId = nextTurnId(TranscriptSpeaker.Assistant)
            outputTurnStatus = VoiceTranscriptStatus.Partial
        }
        activeSpeaker = TranscriptSpeaker.Assistant
        outputTurnTranscript += text
        val transcript = outputTurnTranscript
        recordDiagnostic("output_transcript_delta", "turnId=$outputTurnId, chars=${text.length}")
        recordEvent(
            "voicelab.mobile.transcript.output_delta",
            mapOf("turnId" to outputTurnId) + voiceTextMetadata(key = "text", text = text),
        )
        persistAssistantLocked(suppressed = suppressed)
        transcript
    }

    fun interruptAssistantTurn(suppressed: Boolean) {
        synchronized(lock) {
            if (outputTurnTranscript.isBlank()) return
            if (outputTurnStatus != VoiceTranscriptStatus.Complete) {
                outputTurnStatus = VoiceTranscriptStatus.Interrupted
            }
            persistAssistantLocked(suppressed = suppressed)
        }
    }

    fun completeAssistantTurn(suppressed: Boolean) {
        synchronized(lock) {
            if (
                outputTurnTranscript.isBlank() ||
                suppressed ||
                outputTurnStatus == VoiceTranscriptStatus.Interrupted
            ) {
                return
            }
            outputTurnStatus = VoiceTranscriptStatus.Complete
            persistAssistantLocked(suppressed = suppressed)
        }
    }

    fun persistAssistantForSessionClose(suppressed: Boolean) {
        synchronized(lock) {
            val status = when {
                outputTurnTranscript.isBlank() || outputTurnId.isBlank() -> return
                suppressed -> VoiceTranscriptStatus.Interrupted
                outputTurnStatus == VoiceTranscriptStatus.Interrupted -> VoiceTranscriptStatus.Interrupted
                outputTurnStatus == VoiceTranscriptStatus.Complete -> VoiceTranscriptStatus.Complete
                else -> VoiceTranscriptStatus.SessionClosedBeforeFinal
            }
            persistAssistantLocked(suppressed = suppressed, statusOverride = status)
        }
    }

    fun persistUserForSessionClose() {
        synchronized(lock) {
            persistUserLocked(
                transcript = inputTurnTranscript,
                turnId = inputTurnId,
                status = VoiceTranscriptStatus.SessionClosedBeforeFinal,
            )
        }
    }

    /**
     * Must be called with [lock] held. The persist enqueue is non-blocking (bookkeeping
     * plus a lazily started coroutine); `onPersisted` runs later on the persistence
     * coroutine, never nested inside this hold.
     */
    private fun persistUserLocked(transcript: String, turnId: String, status: VoiceTranscriptStatus) {
        if (transcript.isBlank() || turnId.isBlank()) return
        persist(
            { conversation ->
                transcriptPersister.upsertUserTranscriptTurn(
                    conversation = conversation,
                    text = transcript,
                    turnId = turnId,
                    sessionId = sessionId,
                    status = status,
                )
            },
            {
                recordFinalTranscriptEventsOnce(
                    finalEventName = "voicelab.mobile.transcript.user_final",
                    turnId = turnId,
                    speaker = "user",
                    status = status,
                    textKey = "voice.user_transcript",
                    text = transcript,
                )
            },
        )
    }

    /**
     * Must be called with [lock] held. See [persistUserLocked] for why enqueueing
     * inside the hold is safe.
     */
    private fun persistAssistantLocked(suppressed: Boolean, statusOverride: VoiceTranscriptStatus? = null) {
        val transcript = outputTurnTranscript
        val turnId = outputTurnId
        if (transcript.isBlank() || turnId.isBlank()) return
        val status = statusOverride ?: when {
            outputTurnStatus == VoiceTranscriptStatus.Complete -> VoiceTranscriptStatus.Complete
            suppressed -> VoiceTranscriptStatus.Interrupted
            else -> outputTurnStatus
        }
        persist(
            { conversation ->
                transcriptPersister.upsertAssistantTranscriptTurn(
                    conversation = conversation,
                    text = transcript,
                    interrupted = status == VoiceTranscriptStatus.Interrupted,
                    turnId = turnId,
                    sessionId = sessionId,
                    status = status,
                )
            },
            {
                recordFinalTranscriptEventsOnce(
                    finalEventName = "voicelab.mobile.transcript.assistant_final",
                    turnId = turnId,
                    speaker = "assistant",
                    status = status,
                    textKey = "gemini.output_transcript",
                    text = transcript,
                )
            },
        )
    }

    private fun recordFinalTranscriptEventsOnce(
        finalEventName: String,
        turnId: String,
        speaker: String,
        status: VoiceTranscriptStatus,
        textKey: String,
        text: String,
    ) {
        if (status == VoiceTranscriptStatus.Partial) return
        val telemetryKey = "$finalEventName|$turnId"
        // Invoked from `onPersisted` on the persistence coroutine, outside any mutator's
        // hold — so it takes the lock itself to guard the dedup set.
        val shouldRecord = synchronized(lock) {
            finalTelemetryKeys.add(telemetryKey)
        }
        if (!shouldRecord) return
        val attributes = mapOf(
            "turnId" to turnId,
            "speaker" to speaker,
            "status" to status.statusName,
        ) + voiceTextPayload(key = textKey, text = text)
        recordEvent(finalEventName, attributes)
        recordEvent("voicelab.mobile.transcript.turn", attributes)
    }

    private fun nextTurnId(speaker: TranscriptSpeaker): String {
        turnSequence += 1
        return "${speaker.name.lowercase()}-$turnSequence"
    }
}
