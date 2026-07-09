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
    private val lock = Any()
    private var activeSpeaker: TranscriptSpeaker? = null
    private var inputTurnTranscript = ""
    private var outputTurnTranscript = ""
    private var outputTurnStatus = VoiceTranscriptStatus.Partial
    private var turnSequence = 0L
    private var inputTurnId = ""
    private var outputTurnId = ""
    private val finalTelemetryKeys = mutableSetOf<String>()

    fun appendUserDelta(text: String): String {
        val (transcript, turnId) = synchronized(lock) {
            if (activeSpeaker != TranscriptSpeaker.User) {
                inputTurnTranscript = ""
                inputTurnId = nextTurnId(TranscriptSpeaker.User)
            }
            activeSpeaker = TranscriptSpeaker.User
            inputTurnTranscript += text
            inputTurnTranscript to inputTurnId
        }
        recordDiagnostic("input_transcript_delta", "turnId=$turnId, chars=${text.length}")
        recordEvent(
            "voicelab.mobile.transcript.input_delta",
            mapOf("turnId" to turnId) + voiceTextMetadata(key = "text", text = text),
        )
        persistUser(transcript = transcript, turnId = turnId, status = VoiceTranscriptStatus.Partial)
        return transcript
    }

    fun appendAssistantDelta(text: String, suppressed: Boolean): String {
        val (transcript, turnId) = synchronized(lock) {
            if (activeSpeaker != TranscriptSpeaker.Assistant) {
                outputTurnTranscript = ""
                outputTurnId = nextTurnId(TranscriptSpeaker.Assistant)
                outputTurnStatus = VoiceTranscriptStatus.Partial
            }
            activeSpeaker = TranscriptSpeaker.Assistant
            outputTurnTranscript += text
            outputTurnTranscript to outputTurnId
        }
        recordDiagnostic("output_transcript_delta", "turnId=$turnId, chars=${text.length}")
        recordEvent(
            "voicelab.mobile.transcript.output_delta",
            mapOf("turnId" to turnId) + voiceTextMetadata(key = "text", text = text),
        )
        persistAssistant(suppressed = suppressed)
        return transcript
    }

    fun interruptAssistantTurn(suppressed: Boolean) {
        synchronized(lock) {
            if (outputTurnTranscript.isBlank()) return
            if (outputTurnStatus != VoiceTranscriptStatus.Complete) {
                outputTurnStatus = VoiceTranscriptStatus.Interrupted
            }
        }
        persistAssistant(suppressed = suppressed)
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
        }
        persistAssistant(suppressed = suppressed)
    }

    fun persistAssistantForSessionClose(suppressed: Boolean) {
        val status = synchronized(lock) {
            when {
                outputTurnTranscript.isBlank() || outputTurnId.isBlank() -> return
                suppressed -> VoiceTranscriptStatus.Interrupted
                outputTurnStatus == VoiceTranscriptStatus.Interrupted -> VoiceTranscriptStatus.Interrupted
                outputTurnStatus == VoiceTranscriptStatus.Complete -> VoiceTranscriptStatus.Complete
                else -> VoiceTranscriptStatus.SessionClosedBeforeFinal
            }
        }
        persistAssistant(suppressed = suppressed, statusOverride = status)
    }

    fun persistUserForSessionClose() {
        val (transcript, turnId) = synchronized(lock) {
            inputTurnTranscript to inputTurnId
        }
        if (transcript.isBlank() || turnId.isBlank()) return
        persistUser(transcript = transcript, turnId = turnId, status = VoiceTranscriptStatus.SessionClosedBeforeFinal)
    }

    private fun persistUser(transcript: String, turnId: String, status: VoiceTranscriptStatus) {
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

    private fun persistAssistant(suppressed: Boolean, statusOverride: VoiceTranscriptStatus? = null) {
        val snapshot = synchronized(lock) {
            val transcript = outputTurnTranscript
            val turnId = outputTurnId
            if (transcript.isBlank() || turnId.isBlank()) return
            val status = statusOverride ?: when {
                outputTurnStatus == VoiceTranscriptStatus.Complete -> VoiceTranscriptStatus.Complete
                suppressed -> VoiceTranscriptStatus.Interrupted
                else -> outputTurnStatus
            }
            Triple(transcript, turnId, status)
        }
        val (transcript, turnId, status) = snapshot
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
