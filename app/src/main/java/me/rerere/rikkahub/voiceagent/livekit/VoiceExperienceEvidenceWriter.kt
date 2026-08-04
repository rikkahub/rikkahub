package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifact
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactWriter

internal class VoiceExperienceEvidenceWriter(
    private val artifactWriter: VoiceE2EArtifactWriter,
) : VoiceExperienceEvidenceSink {
    override suspend fun append(event: LiveKitVoiceExperienceEvent) {
        val rawEvent = event.canonicalJson()
        artifactWriter.write(VoiceE2EArtifact.VoiceExperiencePrivate, rawEvent)
        artifactWriter.write(
            VoiceE2EArtifact.VoiceExperienceEvents,
            event.sanitizedJson(rawEvent),
        )
    }
}

private fun LiveKitVoiceExperienceEvent.sanitizedJson(rawEvent: String): String =
    buildJsonObject {
        put("version", version)
        put("voiceSessionHash", voiceSha256(voiceSessionId))
        put("eventId", eventId)
        put("kind", kind)
        put("observedAt", observedAt)
        put("eventHash", voiceSha256(rawEvent))
        when (this@sanitizedJson) {
            is LiveKitVoiceExperienceEvent.JobAccepted -> {
                put("userTurnId", userTurnId)
                put("requestHash", requestHash)
                put("toolCallId", toolCallId)
                put("argumentHash", argumentHash)
                put("jobId", jobId)
                put("ownerHash", ownerHash)
                put("conversationHash", conversationHash)
                put("roomHash", roomHash)
                put("traceHash", traceHash)
                put("promptCharacterCount", prompt.length)
            }

            is LiveKitVoiceExperienceEvent.JobState -> {
                put("userTurnId", userTurnId)
                put("requestHash", requestHash)
                put("toolCallId", toolCallId)
                put("argumentHash", argumentHash)
                put("jobId", jobId)
                put("ownerHash", ownerHash)
                put("conversationHash", conversationHash)
                put("roomHash", roomHash)
                put("traceHash", traceHash)
                resultHash?.let { put("resultHash", it) }
                answer?.let { put("answerCharacterCount", it.length) }
                failureReason?.let { put("failureReasonCharacterCount", it.length) }
            }

            is LiveKitVoiceExperienceEvent.Transcript -> {
                put("turnId", turnId)
                put("role", role)
                put("interrupted", interrupted)
                put("textCharacterCount", text.length)
                groundedJobId?.let { put("groundedJobId", it) }
                groundedResultHash?.let { put("groundedResultHash", it) }
            }

            is LiveKitVoiceExperienceEvent.Delivery -> {
                put("toolCallId", toolCallId)
                put("jobId", jobId)
                assistantTurnId?.let { put("assistantTurnId", it) }
                userSpeaking?.let { put("userSpeaking", it) }
                agentSpeaking?.let { put("agentSpeaking", it) }
            }

            is LiveKitVoiceExperienceEvent.FollowUpCorrelation -> {
                put("followUpTurnId", followUpTurnId)
                put("assistantTurnId", assistantTurnId)
                put("resultHash", resultHash)
            }
        }
    }.let(CanonicalVoiceExperienceJson::encodeObject)
