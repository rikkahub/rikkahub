package me.rerere.rikkahub.voiceagent.telemetry

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class VoiceTraceContext(
    val traceId: String,
    val voiceSessionId: String,
    val sentryTrace: String? = null,
    val sentryBaggage: String? = null,
)

typealias VoiceAttributes = Map<String, Any?>

interface VoiceSpan {
    fun setAttribute(key: String, value: Any?)
    fun setAttributes(attributes: VoiceAttributes)
}

interface VoiceObservability {
    fun withSentryPropagation(trace: VoiceTraceContext): VoiceTraceContext = trace

    fun recordEvent(
        name: String,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes = emptyMap(),
    )

    suspend fun <T> withSpan(
        name: String,
        trace: VoiceTraceContext,
        block: suspend (VoiceSpan) -> T,
    ): T

    fun captureException(
        throwable: Throwable,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes = emptyMap(),
    )
}

internal data class RecordedVoiceEvent(
    val name: String,
    val trace: VoiceTraceContext,
    val attributes: Map<String, Any>,
)

internal enum class RecordedVoiceSpanStatus {
    Ok,
    Error,
}

internal data class RecordedVoiceSpan(
    val name: String,
    val trace: VoiceTraceContext,
    val attributes: Map<String, Any>,
    val status: RecordedVoiceSpanStatus,
)

internal data class RecordedVoiceException(
    val throwable: Throwable,
    val trace: VoiceTraceContext,
    val attributes: Map<String, Any>,
)

private object NoOpVoiceSpan : VoiceSpan {
    override fun setAttribute(key: String, value: Any?) = Unit
    override fun setAttributes(attributes: VoiceAttributes) = Unit
}

object NoOpVoiceObservability : VoiceObservability {
    override fun recordEvent(
        name: String,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) = Unit

    override suspend fun <T> withSpan(
        name: String,
        trace: VoiceTraceContext,
        block: suspend (VoiceSpan) -> T,
    ): T = block(NoOpVoiceSpan)

    override fun captureException(
        throwable: Throwable,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) = Unit
}

internal class RecordingVoiceObservability : VoiceObservability {
    val events = mutableListOf<RecordedVoiceEvent>()
    val spans = mutableListOf<RecordedVoiceSpan>()
    val exceptions = mutableListOf<RecordedVoiceException>()

    override fun recordEvent(
        name: String,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) {
        events += RecordedVoiceEvent(
            name = name,
            trace = trace,
            attributes = attributes.withoutNullValues(),
        )
    }

    override suspend fun <T> withSpan(
        name: String,
        trace: VoiceTraceContext,
        block: suspend (VoiceSpan) -> T,
    ): T {
        val span = MutableVoiceSpan()
        return try {
            val result = block(span)
            recordSpan(name = name, trace = trace, span = span, status = RecordedVoiceSpanStatus.Ok)
            result
        } catch (throwable: Throwable) {
            recordSpan(name = name, trace = trace, span = span, status = RecordedVoiceSpanStatus.Error)
            throw throwable
        }
    }

    override fun captureException(
        throwable: Throwable,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) {
        exceptions += RecordedVoiceException(
            throwable = throwable,
            trace = trace,
            attributes = attributes.withoutNullValues(),
        )
    }

    private fun recordSpan(
        name: String,
        trace: VoiceTraceContext,
        span: MutableVoiceSpan,
        status: RecordedVoiceSpanStatus,
    ) {
        spans += RecordedVoiceSpan(
            name = name,
            trace = trace,
            attributes = span.attributes.toMap(),
            status = status,
        )
    }
}

private class MutableVoiceSpan : VoiceSpan {
    val attributes = mutableMapOf<String, Any>()

    override fun setAttribute(key: String, value: Any?) {
        if (value != null) {
            attributes[key] = value
        }
    }

    override fun setAttributes(attributes: VoiceAttributes) {
        attributes.forEach { (key, value) -> setAttribute(key, value) }
    }
}

internal fun voiceTextPayload(
    key: String,
    text: String,
    previewChars: Int = 4096,
): Map<String, Any> {
    val truncated = text.length > previewChars
    return mapOf(
        key to if (truncated) text.take(previewChars) else text,
        "$key.truncated" to truncated,
        "$key.chars" to text.length,
        "$key.sha256" to sha256Hex(text),
    )
}

internal fun voiceTextMetadata(
    key: String,
    text: String,
): Map<String, Any> =
    mapOf("$key.chars" to text.length)

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val voiceTraceIdRandom = SecureRandom()

internal fun newVoiceTraceId(
    randomInt: (Int) -> Int = voiceTraceIdRandom::nextInt,
): String {
    val value = Math.floorMod(randomInt(VOICE_TRACE_ID_BOUND), VOICE_TRACE_ID_BOUND)
    return String.format(Locale.US, "VA%06d", value)
}

internal fun newVoiceTraceContext(
    randomInt: (Int) -> Int = voiceTraceIdRandom::nextInt,
): VoiceTraceContext {
    val traceId = newVoiceTraceId(randomInt)
    return VoiceTraceContext(
        traceId = traceId,
        voiceSessionId = traceId,
    )
}

private fun VoiceAttributes.withoutNullValues(): Map<String, Any> =
    mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

private const val VOICE_TRACE_ID_BOUND = 1_000_000
