package me.rerere.ai.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.Response

/** A structured error from an externally-callback-driven provider stream. */
enum class ProviderStreamErrorCode {
    STREAM_BACKPRESSURE_EXCEEDED,
    STREAM_UPSTREAM_FAILURE,
    STREAM_MALFORMED_EVENT,
    STREAM_INCOMPLETE,
}

class ProviderStreamException(
    val code: ProviderStreamErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun providerStreamFailure(
    cause: Throwable?,
    httpStatus: Int? = null,
    httpStatusMessage: String? = null,
): ProviderStreamException {
    val resolvedCause = when {
        httpStatus != null -> RuntimeException(
            buildString {
                append("HTTP ")
                append(httpStatus)
                httpStatusMessage?.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
            },
            cause,
        )
        cause != null -> cause
        else -> RuntimeException("Provider stream ended without an error detail")
    }
    return ProviderStreamException(
        ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE,
        "Provider stream failed",
        resolvedCause,
    )
}

/**
 * Keeps enough of a non-stream HTTP failure to diagnose incompatible tool declarations without
 * exposing an unbounded gateway response. Stream requests already preserve this through
 * [providerStreamFailure]; non-stream fallbacks must do the same.
 */
fun providerRequestFailure(response: Response): RuntimeException {
    val rawBody = response.body.string()
    val detail = rawBody.takeIf(String::isNotBlank)?.let { body ->
        runCatching { Json.parseToJsonElement(body).parseErrorDetail().message }.getOrNull()
            ?: body.replace(Regex("\\s+"), " ").take(MAX_PROVIDER_ERROR_BODY_LENGTH)
    }
    val status = buildString {
        append("HTTP ").append(response.code)
        response.message.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
    }
    return RuntimeException(
        listOf("Provider request failed", status, detail)
            .filterNotNull()
            .joinToString(": "),
    )
}

private const val MAX_PROVIDER_ERROR_BODY_LENGTH = 600

enum class ProviderStreamTerminationReason {
    DONE,
    UPSTREAM_FAILURE,
    MALFORMED_EVENT,
    INCOMPLETE,
    BACKPRESSURE_EXCEEDED,
    CANCELLED,
}

data class ProviderStreamDiagnostics(
    val capacity: Int,
    val peakQueueSize: Int,
    val terminationReason: ProviderStreamTerminationReason,
)

/**
 * A bounded, single-consumer bridge for callback APIs such as OkHttp SSE.
 *
 * Callback threads cannot suspend, so a full queue terminates the stream instead of dropping a
 * delta. Buffered values are drained before a terminal failure reaches the flow collector.
 */
class BoundedStreamBridge<T>(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onTerminated: (ProviderStreamDiagnostics) -> Unit = {},
) {
    private val queue = Channel<T>(capacity)
    private val lock = Any()
    private var queued = 0
    private var peakQueueSize = 0
    private var terminationReason: ProviderStreamTerminationReason? = null
    private var cancelUpstream: (() -> Unit)? = null

    init {
        require(capacity > 0) { "Stream queue capacity must be positive" }
    }

    /** Attaches cancellation of the EventSource/Call after it has been created. */
    fun attachUpstreamCanceller(canceller: () -> Unit) {
        val cancelNow = synchronized(lock) {
            if (terminationReason == null) {
                cancelUpstream = canceller
                false
            } else {
                true
            }
        }
        if (cancelNow) cancelSafely(canceller)
    }

    /** Returns false only after this bridge has already terminated. */
    fun emit(value: T): Boolean {
        var upstreamToCancel: (() -> Unit)? = null
        var diagnostics: ProviderStreamDiagnostics? = null
        synchronized(lock) {
            if (terminationReason != null) return false

            // Increment before trySend so the consumer cannot observe a successful send first.
            queued += 1
            peakQueueSize = maxOf(peakQueueSize, queued)
            if (queue.trySend(value).isSuccess) return true

            queued -= 1
            peakQueueSize = minOf(peakQueueSize, queued)
            val error = ProviderStreamException(
                code = ProviderStreamErrorCode.STREAM_BACKPRESSURE_EXCEEDED,
                message = "Provider stream queue reached its capacity of $capacity",
            )
            val termination = terminateLocked(
                reason = ProviderStreamTerminationReason.BACKPRESSURE_EXCEEDED,
                error = error,
                discardQueuedValues = false,
            )
            upstreamToCancel = termination.first
            diagnostics = termination.second
        }
        upstreamToCancel?.let(::cancelSafely)
        diagnostics?.let(onTerminated)
        return false
    }

    /** Completes normally while preserving every already accepted value. */
    fun complete() {
        terminate(
            reason = ProviderStreamTerminationReason.DONE,
            error = null,
            discardQueuedValues = false,
        )
    }

    fun fail(
        error: Throwable,
        reason: ProviderStreamTerminationReason = ProviderStreamTerminationReason.UPSTREAM_FAILURE,
    ) {
        terminate(reason, error, discardQueuedValues = false)
    }

    /** Preserves accepted partial chunks while surfacing a missing provider completion signal. */
    fun failIncomplete() {
        fail(
            ProviderStreamException(
                code = ProviderStreamErrorCode.STREAM_INCOMPLETE,
                message = "Provider stream closed before an explicit completion signal",
            ),
            ProviderStreamTerminationReason.INCOMPLETE,
        )
    }

    /** Cancels the upstream call and discards work which no collector can consume. */
    fun cancel() {
        terminate(
            reason = ProviderStreamTerminationReason.CANCELLED,
            error = null,
            discardQueuedValues = true,
        )
    }

    suspend fun receive(): T {
        val value = queue.receive()
        synchronized(lock) {
            queued -= 1
        }
        return value
    }

    private fun terminate(
        reason: ProviderStreamTerminationReason,
        error: Throwable?,
        discardQueuedValues: Boolean,
    ) {
        var upstreamToCancel: (() -> Unit)? = null
        var diagnostics: ProviderStreamDiagnostics? = null
        synchronized(lock) {
            if (terminationReason != null) return
            val termination = terminateLocked(reason, error, discardQueuedValues)
            upstreamToCancel = termination.first
            diagnostics = termination.second
        }
        upstreamToCancel?.let(::cancelSafely)
        diagnostics?.let(onTerminated)
    }

    private fun terminateLocked(
        reason: ProviderStreamTerminationReason,
        error: Throwable?,
        discardQueuedValues: Boolean,
    ): Pair<(() -> Unit)?, ProviderStreamDiagnostics> {
        terminationReason = reason
        if (discardQueuedValues) {
            queue.cancel()
        } else {
            queue.close(error)
        }
        val upstreamToCancel = cancelUpstream
        cancelUpstream = null
        return upstreamToCancel to ProviderStreamDiagnostics(capacity, peakQueueSize, reason)
    }

    private fun cancelSafely(canceller: () -> Unit) {
        runCatching(canceller)
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
    }
}

fun <T> boundedStreamFlow(
    capacity: Int = BoundedStreamBridge.DEFAULT_CAPACITY,
    onTerminated: (ProviderStreamDiagnostics) -> Unit = {},
    start: suspend (BoundedStreamBridge<T>) -> Unit,
): Flow<T> = flow {
    val bridge = BoundedStreamBridge<T>(capacity, onTerminated)
    try {
        try {
            start(bridge)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            bridge.fail(
                ProviderStreamException(
                    code = ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE,
                    message = "Unable to start provider stream",
                    cause = error,
                )
            )
        }
        while (true) {
            emit(bridge.receive())
        }
    } catch (_: ClosedReceiveChannelException) {
        // A normal DONE closes the queue after all accepted values have been drained.
    } finally {
        bridge.cancel()
    }
}
