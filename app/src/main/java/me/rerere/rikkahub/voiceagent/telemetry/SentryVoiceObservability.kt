package me.rerere.rikkahub.voiceagent.telemetry

import android.content.Context
import android.util.Log
import io.sentry.IScope
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CancellationException

data class SentryVoiceObservabilityConfig(
    val dsn: String,
    val environment: String = "development",
    val tracesSampleRate: Double = 0.0,
)

class SentryVoiceObservability : VoiceObservability {
    private val activeVoiceTransactions = mutableMapOf<String, ActiveVoiceTransaction>()

    override fun withSentryPropagation(trace: VoiceTraceContext): VoiceTraceContext {
        synchronized(activeVoiceTransactions) {
            activeVoiceTransactions[trace.voiceSessionId]
                ?.takeIf { it.matches(trace) && !it.transaction.isFinished }
                ?.let { active ->
                    return trace.withPropagationHeadersFrom(active.transaction)
                }
            activeVoiceTransactions.remove(trace.voiceSessionId)
                ?.transaction
                ?.takeUnless { it.isFinished }
                ?.finish(SpanStatus.OK)
        }

        val transaction = Sentry.startTransaction(
            "voice.session",
            "voice.session",
            TransactionOptions().apply {
                isBindToScope = false
                isWaitForChildren = true
            },
        ).apply {
            setTag("service", ANDROID_SERVICE)
            setTag("voice_trace_id", trace.traceId)
            setTag("voice_session_id", trace.voiceSessionId)
        }

        val enrichedTrace = trace.withPropagationHeadersFrom(transaction)
        if (enrichedTrace.sentryTrace != null) {
            synchronized(activeVoiceTransactions) {
                activeVoiceTransactions[trace.voiceSessionId] = ActiveVoiceTransaction(
                    traceId = trace.traceId,
                    voiceSessionId = trace.voiceSessionId,
                    transaction = transaction,
                )
            }
        }
        return enrichedTrace
    }

    override fun recordEvent(
        name: String,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) {
        val activeTransaction = activeTransactionFor(trace)
        Sentry.captureMessage(name, SentryLevel.INFO) { scope ->
            applyVoiceTrace(scope, trace, activeTransaction, attributes)
        }
        if (name == "voicelab.mobile.session.ended") {
            finishActiveTransaction(trace, SpanStatus.OK)
        }
    }

    override suspend fun <T> withSpan(
        name: String,
        trace: VoiceTraceContext,
        block: suspend (VoiceSpan) -> T,
    ): T {
        val sentrySpan = activeTransactionFor(trace)?.startChild(name, name)
        val span = MutableSentryVoiceSpan(sentrySpan)
        recordEvent("$name.started", trace)
        return try {
            val result = block(span)
            sentrySpan?.finish(SpanStatus.OK)
            recordEvent("$name.succeeded", trace, span.attributes)
            result
        } catch (cancellation: CancellationException) {
            sentrySpan?.finish(SpanStatus.CANCELLED)
            throw cancellation
        } catch (throwable: Throwable) {
            sentrySpan?.setThrowable(throwable)
            sentrySpan?.finish(SpanStatus.INTERNAL_ERROR)
            recordEvent("$name.failed", trace, span.attributes)
            captureException(throwable, trace, span.attributes)
            throw throwable
        }
    }

    private fun activeTransactionFor(trace: VoiceTraceContext): ITransaction? =
        synchronized(activeVoiceTransactions) {
            activeVoiceTransactions[trace.voiceSessionId]
                ?.takeIf { it.matches(trace) && !it.transaction.isFinished }
                ?.transaction
        }

    private fun finishActiveTransaction(trace: VoiceTraceContext, status: SpanStatus) {
        val active = synchronized(activeVoiceTransactions) {
            activeVoiceTransactions[trace.voiceSessionId]
                ?.takeIf { it.matches(trace) }
                ?.also { activeVoiceTransactions.remove(trace.voiceSessionId) }
        }
        active?.transaction?.takeUnless { it.isFinished }?.finish(status)
    }

    override fun captureException(
        throwable: Throwable,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) {
        val activeTransaction = activeTransactionFor(trace)
        Sentry.withScope { scope ->
            applyVoiceTrace(scope, trace, activeTransaction, attributes)
            Sentry.captureException(throwable)
        }
    }
}

fun createSentryVoiceObservability(
    context: Context,
    config: SentryVoiceObservabilityConfig,
): VoiceObservability {
    if (config.dsn.isBlank()) {
        return NoOpVoiceObservability
    }

    return runCatching {
        SentryAndroid.init(context) { options ->
            options.dsn = config.dsn
            options.environment = config.environment.ifBlank { "development" }
            options.tracesSampleRate = config.tracesSampleRate.coerceIn(0.0, 1.0)
        }
        SentryVoiceObservability()
    }.getOrElse { error ->
        Log.w(TAG, "Sentry init failed; continuing without voice observability", error)
        NoOpVoiceObservability
    }
}

private class MutableSentryVoiceSpan(
    private val sentrySpan: ISpan?,
) : VoiceSpan {
    val attributes = mutableMapOf<String, Any?>()

    override fun setAttribute(key: String, value: Any?) {
        if (value != null) {
            attributes[key] = value
            sentrySpan?.setData(key, value.toString())
        }
    }

    override fun setAttributes(attributes: VoiceAttributes) {
        attributes.forEach { (key, value) -> setAttribute(key, value) }
    }
}

private fun applyVoiceTrace(
    scope: IScope,
    trace: VoiceTraceContext,
    activeTransaction: ISpan?,
    attributes: VoiceAttributes,
) {
    activeTransaction?.let(scope::setActiveSpan)
    scope.setTag("service", ANDROID_SERVICE)
    scope.setTag("voice_trace_id", trace.traceId)
    scope.setTag("voice_session_id", trace.voiceSessionId)
    scope.setExtra("service", ANDROID_SERVICE)
    scope.setExtra("traceId", trace.traceId)
    scope.setExtra("voiceSessionId", trace.voiceSessionId)
    attributes.withoutNullValues().forEach { (key, value) ->
        scope.setExtra(key, value.toString())
    }
}

private fun VoiceAttributes.withoutNullValues(): Map<String, Any> =
    mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

private fun VoiceTraceContext.withPropagationHeadersFrom(span: ISpan): VoiceTraceContext =
    span.toSentryTrace().value
        .takeIf(::isUsableSentryTrace)
        ?.let { sentryTrace ->
            copy(
                sentryTrace = sentryTrace,
                sentryBaggage = span.toBaggageHeader(emptyList())?.value,
            )
        }
        ?: this

private fun isUsableSentryTrace(value: String): Boolean =
    value.isNotBlank() &&
        !value.startsWith("00000000000000000000000000000000-") &&
        !value.endsWith("-0")

private const val TAG = "SentryVoiceObservability"
private const val ANDROID_SERVICE = "android"

private data class ActiveVoiceTransaction(
    val traceId: String,
    val voiceSessionId: String,
    val transaction: ITransaction,
) {
    fun matches(trace: VoiceTraceContext): Boolean =
        traceId == trace.traceId && voiceSessionId == trace.voiceSessionId
}
