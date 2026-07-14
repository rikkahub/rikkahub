@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package me.rerere.rikkahub.voiceagent.hermesvoice

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.utils.JsonInstant

@Serializable
data class MobileVoiceSessionRequest(
    val modelId: String,
)

@Serializable
data class VoiceTraceMetadata(
    val traceId: String,
    val voiceSessionId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sentryTrace: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sentryBaggage: String? = null,
)

@Serializable
data class VoiceSessionConfig(
    val token: String,
    val modelId: String,
    val providerModel: String,
    val apiVersion: String,
    val websocketUrl: String,
    val inputSampleRate: Int,
    val outputSampleRate: Int,
    val liveConnectConfig: JsonObject,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val trace: VoiceTraceMetadata? = null,
) {
    override fun toString(): String =
        "VoiceSessionConfig(" +
            "token=[redacted], " +
            "modelId=$modelId, " +
            "providerModel=$providerModel, " +
            "apiVersion=$apiVersion, " +
            "websocketUrl=[redacted], " +
            "inputSampleRate=$inputSampleRate, " +
            "outputSampleRate=$outputSampleRate, " +
            "liveConnectConfig=[redacted], " +
            "trace=$trace" +
            ")"
}

typealias MobileVoiceSessionResponse = VoiceSessionConfig

@Serializable
enum class HermesJobStatus {
    @SerialName("accepted")
    Accepted,

    @SerialName("queued")
    Queued,

    @SerialName("running")
    Running,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("failed")
    Failed,

    @SerialName("expired")
    Expired,

    @SerialName("canceled")
    Canceled,
    ;

    companion object {
        /** The single wire-status parser. Returns null for unknown statuses. */
        fun parse(raw: String?): HermesJobStatus? = when (raw?.lowercase()) {
            "accepted" -> Accepted
            "queued" -> Queued
            "running" -> Running
            "succeeded" -> Succeeded
            "failed" -> Failed
            "expired", "timeout" -> Expired
            "canceled", "cancelled" -> Canceled
            else -> null
        }
    }
}

@Serializable
enum class VoiceFailureKind {
    @SerialName("auth")
    Auth,

    @SerialName("validation")
    Validation,

    @SerialName("rate_limited")
    RateLimited,

    @SerialName("hermes_unavailable")
    HermesUnavailable,

    @SerialName("hermes_timeout")
    HermesTimeout,

    @SerialName("hermes_failed")
    HermesFailed,

    @SerialName("canceled")
    Canceled,

    @SerialName("expired")
    Expired,

    @SerialName("internal")
    Internal,

    @SerialName("gemini")
    Gemini,

    @SerialName("android")
    Android,
}

@Serializable
enum class VoiceFailureSource {
    @SerialName("hermes_voice")
    HermesVoice,

    @SerialName("hermes")
    Hermes,

    @SerialName("gemini")
    Gemini,

    @SerialName("android")
    Android,
}

@Serializable
data class VoiceFailure(
    val kind: VoiceFailureKind,
    val safeMessage: String,
    val safeSummary: String,
    val retryable: Boolean,
    val source: VoiceFailureSource,
)

@Serializable
data class HermesToolInvocation(
    val callId: String,
    val prompt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val profileId: String? = null,
) {
    override fun toString(): String =
        "HermesToolInvocation(callId=$callId, prompt=[redacted], profileId=$profileId)"
}

typealias MobileHermesRequest = HermesToolInvocation

@Serializable
data class MobileHermesResponse(
    val callId: String,
    val answer: String,
    val model: String,
    val profileId: String,
    val profileLabel: String,
    val elapsedMs: Long? = null,
) {
    override fun toString(): String =
        "MobileHermesResponse(" +
            "callId=$callId, " +
            "answer=[redacted], " +
            "model=$model, " +
            "profileId=$profileId, " +
            "profileLabel=$profileLabel, " +
            "elapsedMs=$elapsedMs" +
            ")"
}

@Serializable
data class HermesJobSnapshot(
    val jobId: String,
    val callId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val prompt: String? = null,
    val status: HermesJobStatus,
    val createdAt: String,
    val updatedAt: String? = null,
    val completedAt: String? = null,
    val answer: String? = null,
    val model: String? = null,
    val profileId: String? = null,
    val profileLabel: String? = null,
    val elapsedMs: Long? = null,
    val failure: VoiceFailure? = null,
) {
    override fun toString(): String =
        "HermesJobSnapshot(" +
            "jobId=$jobId, " +
            "callId=$callId, " +
            "prompt=[redacted], " +
            "status=$status, " +
            "createdAt=$createdAt, " +
            "updatedAt=$updatedAt, " +
            "completedAt=$completedAt, " +
            "answer=${answer?.let { "[redacted]" }}, " +
            "model=$model, " +
            "profileId=$profileId, " +
            "profileLabel=$profileLabel, " +
            "elapsedMs=$elapsedMs, " +
            "failure=$failure" +
            ")"
}

@Serializable
internal data class MobileHermesJobSnapshotWire(
    val jobId: String,
    val callId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val prompt: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String? = null,
    val completedAt: String? = null,
    val answer: String? = null,
    val model: String? = null,
    val profileId: String? = null,
    val profileLabel: String? = null,
    val elapsedMs: Long? = null,
    val failure: JsonElement? = null,
)

internal fun MobileHermesJobSnapshotWire.toHermesJobSnapshot(): HermesJobSnapshot {
    val parsedStatus = HermesJobStatus.parse(status)
    val parsedFailure = if (parsedStatus == null) {
        unknownHermesStatusFailure(status)
    } else {
        failure?.let {
            runCatching { JsonInstant.decodeFromJsonElement<VoiceFailure>(it) }.getOrNull()
        }
    }
    return HermesJobSnapshot(
        jobId = jobId,
        callId = callId,
        prompt = prompt,
        status = parsedStatus ?: HermesJobStatus.Failed,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        answer = answer,
        model = model,
        profileId = profileId,
        profileLabel = profileLabel,
        elapsedMs = elapsedMs,
        failure = parsedFailure,
    )
}

private fun unknownHermesStatusFailure(raw: String): VoiceFailure {
    val safeMessage = "Unknown Hermes job status: $raw"
    return VoiceFailure(
        kind = VoiceFailureKind.Internal,
        safeMessage = safeMessage,
        safeSummary = safeMessage,
        retryable = false,
        source = VoiceFailureSource.HermesVoice,
    )
}

typealias MobileHermesJobSubmitResponse = HermesJobSnapshot
typealias MobileHermesJobPollResponse = HermesJobSnapshot

@Serializable
data class HermesVoiceCredentials(
    val deviceApiKey: String,
) {
    override fun toString(): String =
        "HermesVoiceCredentials(deviceApiKey=[redacted])"
}
