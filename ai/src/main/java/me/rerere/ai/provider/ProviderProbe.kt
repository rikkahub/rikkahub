package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A model-list probe result: the [ProbeOutcome] [classifyProviderConnection] consumes, plus the
 * actual [models] parsed from a 2xx provider-shaped response (empty otherwise). One HTTP round-trip
 * yields both — the classifier needs only the response shape, the model browser needs the catalog.
 */
data class ModelListProbe(
    val outcome: ProbeOutcome,
    val models: List<Model>,
)

/**
 * Map a transport-layer throwable to the [ProbeOutcome.TransportError] taxonomy. A
 * [CancellationException] is NOT a transport error — it means the probe was cancelled, not that the
 * endpoint is unreachable — so callers rethrow it before reaching this; it is intentionally absent.
 */
internal fun Throwable.toTransportError(): ProbeOutcome.TransportError = when (this) {
    is UnknownHostException -> ProbeOutcome.TransportError.DNS
    is SocketTimeoutException -> ProbeOutcome.TransportError.TIMEOUT
    is ConnectException -> ProbeOutcome.TransportError.CONNECT
    is SSLException -> ProbeOutcome.TransportError.SSL
    is IOException -> ProbeOutcome.TransportError.IO
    else -> ProbeOutcome.TransportError.OTHER
}

private val PROBE_ERROR_FIELDS = listOf("error", "detail", "message", "description")

/**
 * Classify a response body that is NOT a recognized success payload into a [ProbeOutcome.Body]:
 * a provider-shaped JSON error object, JSON of an unexpected shape, or not JSON at all. The
 * model-list and chat probes share this for everything except their own success shape — so an empty
 * 200, an HTML proxy page, and a JSON `{"error": …}` stay distinguishable to the classifier.
 */
internal fun bodyShapeOf(body: String): ProbeOutcome.Body {
    val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
        ?: return ProbeOutcome.Body.NotJson
    val obj = element as? JsonObject ?: return ProbeOutcome.Body.JsonWrongShape
    val hasErrorField = PROBE_ERROR_FIELDS.any { obj[it] != null }
    return if (hasErrorField) ProbeOutcome.Body.ProviderError else ProbeOutcome.Body.JsonWrongShape
}

/**
 * Run a model-list HTTP probe: execute [request], and on a 2xx response parse models with
 * [parseModels]. A 2xx body that parses to ≥1 model is [ProbeOutcome.Body.ModelList]; a 2xx body
 * that yields no models (or doesn't parse) is shape-classified, so an empty/garbage 200 is never
 * mistaken for a real catalog. Non-2xx and transport failures preserve status / body shape for the
 * classifier. [CancellationException] propagates — it is not a connection verdict.
 */
internal suspend fun runModelListProbe(
    client: OkHttpClient,
    request: Request,
    parseModels: (String) -> List<Model>,
): ModelListProbe {
    // await() + body read share one try: a connection failure OR a mid-body read failure both mean
    // no usable response arrived → Transport. Cancellation is never a verdict, so it propagates.
    val (status, bodyStr) = try {
        val response = client.newCall(request).await()
        response.code to response.body.string()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return ModelListProbe(ProbeOutcome.Transport(e.toTransportError()), emptyList())
    }
    if (status in 200..299) {
        val models = runCatching { parseModels(bodyStr) }.getOrNull().orEmpty()
        return if (models.isNotEmpty()) {
            ModelListProbe(
                ProbeOutcome.Http(status, ProbeOutcome.Body.ModelList(models.size)),
                models,
            )
        } else {
            ModelListProbe(ProbeOutcome.Http(status, bodyShapeOf(bodyStr)), emptyList())
        }
    }
    return ModelListProbe(ProbeOutcome.Http(status, bodyShapeOf(bodyStr)), emptyList())
}

/**
 * Run a chat HTTP probe: execute [request] and reduce it to a [ProbeOutcome]. A 2xx body that
 * [looksLikeChat] is [ProbeOutcome.Body.ChatOk]; any other body is shape-classified, so the
 * classifier can tell an authed model-not-found 4xx from a wrong-endpoint HTML page.
 * [CancellationException] propagates.
 */
internal suspend fun runChatProbe(
    client: OkHttpClient,
    request: Request,
    looksLikeChat: (String) -> Boolean,
): ProbeOutcome {
    val (status, bodyStr) = try {
        val response = client.newCall(request).await()
        response.code to response.body.string()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return ProbeOutcome.Transport(e.toTransportError())
    }
    val body = when {
        status in 200..299 && looksLikeChat(bodyStr) -> ProbeOutcome.Body.ChatOk
        status in 200..299 -> ProbeOutcome.Body.JsonWrongShape
        else -> bodyShapeOf(bodyStr)
    }
    return ProbeOutcome.Http(status, body)
}

/** True when [body] is a JSON object containing [field] (used to recognize a provider's chat shape). */
internal fun jsonObjectHasField(body: String, field: String): Boolean =
    runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get(field) != null }
        .getOrDefault(false)
