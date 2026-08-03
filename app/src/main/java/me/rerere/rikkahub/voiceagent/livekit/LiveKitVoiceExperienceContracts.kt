package me.rerere.rikkahub.voiceagent.livekit

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val LIVEKIT_EXPERIENCE_IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,128}$")
private val LIVEKIT_EXPERIENCE_HASH = Regex("^sha256:[0-9a-f]{64}$")

private val LIVEKIT_EXPERIENCE_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

@Serializable
internal data class LiveKitJobCorrelation(
    val ownerHash: String,
    val conversationHash: String,
    val voiceSessionHash: String,
    val roomHash: String,
    val traceHash: String,
) {
    fun isValid(voiceSessionId: String): Boolean =
        listOf(ownerHash, conversationHash, voiceSessionHash, roomHash, traceHash)
            .all(String::isLiveKitExperienceHash) &&
            voiceSessionHash == voiceSha256(voiceSessionId)
}

@Serializable
internal sealed interface LiveKitVoiceExperienceEvent {
    val version: Int
    val voiceSessionId: String
    val eventId: String
    val kind: String
    val observedAt: String

    @Serializable
    data class JobAccepted(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val userTurnId: String,
        val requestHash: String,
        val toolCallId: String,
        val argumentHash: String,
        val jobId: String,
        val ownerHash: String,
        val conversationHash: String,
        val voiceSessionHash: String,
        val roomHash: String,
        val traceHash: String,
        val prompt: String,
    ) : LiveKitVoiceExperienceEvent {
        fun correlation(): LiveKitJobCorrelation = LiveKitJobCorrelation(
            ownerHash = ownerHash,
            conversationHash = conversationHash,
            voiceSessionHash = voiceSessionHash,
            roomHash = roomHash,
            traceHash = traceHash,
        )
    }

    @Serializable
    data class JobState(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val userTurnId: String,
        val requestHash: String,
        val toolCallId: String,
        val argumentHash: String,
        val jobId: String,
        val ownerHash: String,
        val conversationHash: String,
        val voiceSessionHash: String,
        val roomHash: String,
        val traceHash: String,
        val resultHash: String? = null,
        val answer: String? = null,
        val failureReason: String? = null,
    ) : LiveKitVoiceExperienceEvent {
        fun correlation(): LiveKitJobCorrelation = LiveKitJobCorrelation(
            ownerHash = ownerHash,
            conversationHash = conversationHash,
            voiceSessionHash = voiceSessionHash,
            roomHash = roomHash,
            traceHash = traceHash,
        )
    }

    @Serializable
    data class Transcript(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val turnId: String,
        val role: String,
        val text: String,
        val interrupted: Boolean,
        val groundedJobId: String? = null,
        val groundedResultHash: String? = null,
    ) : LiveKitVoiceExperienceEvent

    @Serializable
    data class Delivery(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val toolCallId: String,
        val jobId: String,
        val assistantTurnId: String? = null,
        val userSpeaking: Boolean? = null,
        val agentSpeaking: Boolean? = null,
    ) : LiveKitVoiceExperienceEvent

    @Serializable
    data class FollowUpCorrelation(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val followUpTurnId: String,
        val assistantTurnId: String,
        val resultHash: String,
    ) : LiveKitVoiceExperienceEvent
}

@Serializable
internal data class LiveKitPersistenceAck(
    val version: Int,
    val voiceSessionId: String,
    val eventId: String,
    val status: String,
    val persistedAt: String,
) {
    fun canonicalJson(): String = LIVEKIT_EXPERIENCE_JSON.encodeToString(this)
}

internal fun parseLiveKitVoiceExperienceEvent(payload: String): LiveKitVoiceExperienceEvent? {
    val objectValue = runCatching {
        LIVEKIT_EXPERIENCE_JSON.parseToJsonElement(payload).jsonObject
    }.getOrNull() ?: return null
    val kind = objectValue.string("kind") ?: return null
    val event = when (kind) {
        "job_accepted" -> decodeExact<LiveKitVoiceExperienceEvent.JobAccepted>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS + "prompt",
        )

        "job_running",
        "still_working",
            -> decodeExact<LiveKitVoiceExperienceEvent.JobState>(
                payload = payload,
                objectValue = objectValue,
                requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS,
            )

        "job_succeeded" -> decodeExact<LiveKitVoiceExperienceEvent.JobState>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS + setOf("resultHash", "answer"),
        )

        "job_failed",
        "job_expired",
        "job_canceled",
            -> decodeExact<LiveKitVoiceExperienceEvent.JobState>(
                payload = payload,
                objectValue = objectValue,
                requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS + "failureReason",
            )

        "transcript" -> decodeExact<LiveKitVoiceExperienceEvent.Transcript>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + setOf("turnId", "role", "text", "interrupted"),
            optionalKeys = setOf("groundedJobId", "groundedResultHash"),
        )

        "delivery_eligible",
        "delivery_started",
        "speech_started",
            -> decodeExact<LiveKitVoiceExperienceEvent.Delivery>(
                payload = payload,
                objectValue = objectValue,
                requiredKeys = BASE_EVENT_KEYS + DELIVERY_KEYS,
            )

        "delivery_blocked" -> decodeExact<LiveKitVoiceExperienceEvent.Delivery>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + DELIVERY_KEYS + setOf("userSpeaking", "agentSpeaking"),
        )

        "delivery_announced" -> decodeExact<LiveKitVoiceExperienceEvent.Delivery>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + DELIVERY_KEYS + "assistantTurnId",
        )

        "follow_up_correlation" -> decodeExact<LiveKitVoiceExperienceEvent.FollowUpCorrelation>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + FOLLOW_UP_CORRELATION_KEYS,
        )

        else -> null
    } ?: return null
    return event.takeIf { it.isValid() }
}

internal fun parseLiveKitPersistenceAck(payload: String): LiveKitPersistenceAck? {
    val objectValue = runCatching {
        LIVEKIT_EXPERIENCE_JSON.parseToJsonElement(payload).jsonObject
    }.getOrNull() ?: return null
    val ack = decodeExact<LiveKitPersistenceAck>(
        payload = payload,
        objectValue = objectValue,
        requiredKeys = ACK_KEYS,
    ) ?: return null
    if (
        ack.version != LIVEKIT_EXPERIENCE_VERSION ||
        !ack.voiceSessionId.isLiveKitExperienceIdentifier() ||
        !ack.eventId.isLiveKitExperienceIdentifier() ||
        ack.status != LIVEKIT_PERSISTED_STATUS ||
        !ack.persistedAt.isCanonicalUtcTimestamp()
    ) return null
    return ack
}

internal fun LiveKitVoiceExperienceEvent.canonicalJson(): String = when (this) {
    is LiveKitVoiceExperienceEvent.JobAccepted -> LIVEKIT_EXPERIENCE_JSON.encodeToString(this)
    is LiveKitVoiceExperienceEvent.JobState -> LIVEKIT_EXPERIENCE_JSON.encodeToString(this)
    is LiveKitVoiceExperienceEvent.Transcript -> LIVEKIT_EXPERIENCE_JSON.encodeToString(this)
    is LiveKitVoiceExperienceEvent.Delivery -> LIVEKIT_EXPERIENCE_JSON.encodeToString(this)
    is LiveKitVoiceExperienceEvent.FollowUpCorrelation -> LIVEKIT_EXPERIENCE_JSON.encodeToString(this)
}

internal fun voiceSha256(text: String): String =
    "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

private inline fun <reified T> decodeExact(
    payload: String,
    objectValue: JsonObject,
    requiredKeys: Set<String>,
    optionalKeys: Set<String> = emptySet(),
): T? {
    if (!objectValue.keys.containsAll(requiredKeys)) return null
    if (!requiredKeys.plus(optionalKeys).containsAll(objectValue.keys)) return null
    val decoded = runCatching {
        LIVEKIT_EXPERIENCE_JSON.decodeFromString<T>(payload)
    }.getOrNull() ?: return null
    if (LIVEKIT_EXPERIENCE_JSON.encodeToString(decoded) != payload) return null
    return decoded
}

private fun LiveKitVoiceExperienceEvent.isValid(): Boolean {
    if (
        version != LIVEKIT_EXPERIENCE_VERSION ||
        !voiceSessionId.isLiveKitExperienceIdentifier() ||
        !eventId.isLiveKitExperienceIdentifier() ||
        !observedAt.isCanonicalUtcTimestamp()
    ) return false
    return when (this) {
        is LiveKitVoiceExperienceEvent.JobAccepted ->
            kind == "job_accepted" &&
                hasValidJobCorrelation() &&
                prompt.isNotBlank()

        is LiveKitVoiceExperienceEvent.JobState -> when (kind) {
            "job_running",
            "still_working",
                -> hasValidJobCorrelation() &&
                    resultHash == null &&
                    answer == null &&
                    failureReason == null

            "job_succeeded" ->
                hasValidJobCorrelation() &&
                    answer?.isNotBlank() == true &&
                    resultHash == voiceSha256(answer) &&
                    failureReason == null

            "job_failed",
            "job_expired",
            "job_canceled",
                -> hasValidJobCorrelation() &&
                    failureReason.isSafeFailureReason() &&
                    resultHash == null &&
                    answer == null

            else -> false
        }

        is LiveKitVoiceExperienceEvent.Transcript ->
            kind == "transcript" &&
                turnId.isLiveKitExperienceIdentifier() &&
                role in TRANSCRIPT_ROLES &&
                text.isNotBlank() &&
                (role != "user" || !interrupted) &&
                hasValidGrounding()

        is LiveKitVoiceExperienceEvent.Delivery ->
            toolCallId.isLiveKitExperienceIdentifier() &&
                jobId.isLiveKitExperienceIdentifier() &&
                when (kind) {
                    "delivery_eligible",
                    "delivery_started",
                    "speech_started",
                        -> assistantTurnId == null && userSpeaking == null && agentSpeaking == null

                    "delivery_blocked" ->
                        assistantTurnId == null && userSpeaking != null && agentSpeaking != null

                    "delivery_announced" ->
                        assistantTurnId?.isLiveKitExperienceIdentifier() == true &&
                            userSpeaking == null &&
                            agentSpeaking == null

                    else -> false
                }

        is LiveKitVoiceExperienceEvent.FollowUpCorrelation ->
            kind == "follow_up_correlation" &&
                followUpTurnId.isLiveKitExperienceIdentifier() &&
                assistantTurnId.isLiveKitExperienceIdentifier() &&
                resultHash.isLiveKitExperienceHash()
    }
}

private fun LiveKitVoiceExperienceEvent.JobAccepted.hasValidJobCorrelation(): Boolean =
    userTurnId.isLiveKitExperienceIdentifier() &&
        requestHash.isLiveKitExperienceHash() &&
        toolCallId.isLiveKitExperienceIdentifier() &&
        argumentHash.isLiveKitExperienceHash() &&
        jobId.isLiveKitExperienceIdentifier() &&
        correlation().isValid(voiceSessionId)

private fun LiveKitVoiceExperienceEvent.JobState.hasValidJobCorrelation(): Boolean =
    userTurnId.isLiveKitExperienceIdentifier() &&
        requestHash.isLiveKitExperienceHash() &&
        toolCallId.isLiveKitExperienceIdentifier() &&
        argumentHash.isLiveKitExperienceHash() &&
        jobId.isLiveKitExperienceIdentifier() &&
        correlation().isValid(voiceSessionId)

private fun LiveKitVoiceExperienceEvent.Transcript.hasValidGrounding(): Boolean {
    val bothGrounded = groundedJobId != null && groundedResultHash != null
    val neitherGrounded = groundedJobId == null && groundedResultHash == null
    if (!bothGrounded && !neitherGrounded) return false
    if (role == "user") return neitherGrounded
    return neitherGrounded || (
        groundedJobId?.isLiveKitExperienceIdentifier() == true &&
            groundedResultHash?.isLiveKitExperienceHash() == true
        )
}

private fun String?.isSafeFailureReason(): Boolean =
    this != null &&
        isNotBlank() &&
        length <= MAX_FAILURE_REASON_LENGTH &&
        none(Char::isISOControl)

private fun String.isLiveKitExperienceIdentifier(): Boolean =
    LIVEKIT_EXPERIENCE_IDENTIFIER.matches(this)

private fun String.isLiveKitExperienceHash(): Boolean =
    LIVEKIT_EXPERIENCE_HASH.matches(this)

private fun String.isCanonicalUtcTimestamp(): Boolean {
    if (startsWith("0000-")) return false
    val instant = runCatching { Instant.parse(this) }.getOrNull() ?: return false
    return DateTimeFormatter.ISO_INSTANT.format(instant) == this
}

private fun JsonObject.string(key: String): String? =
    runCatching { getValue(key).jsonPrimitive.content }.getOrNull()

private const val LIVEKIT_EXPERIENCE_VERSION = 1
private const val LIVEKIT_PERSISTED_STATUS = "persisted"
private const val MAX_FAILURE_REASON_LENGTH = 512
private val TRANSCRIPT_ROLES = setOf("user", "assistant")
private val BASE_EVENT_KEYS =
    setOf("version", "voiceSessionId", "eventId", "kind", "observedAt")
private val JOB_CORRELATION_KEYS =
    setOf(
        "userTurnId",
        "requestHash",
        "toolCallId",
        "argumentHash",
        "jobId",
        "ownerHash",
        "conversationHash",
        "voiceSessionHash",
        "roomHash",
        "traceHash",
    )
private val DELIVERY_KEYS = setOf("toolCallId", "jobId")
private val FOLLOW_UP_CORRELATION_KEYS =
    setOf("followUpTurnId", "assistantTurnId", "resultHash")
private val ACK_KEYS =
    setOf("version", "voiceSessionId", "eventId", "status", "persistedAt")
