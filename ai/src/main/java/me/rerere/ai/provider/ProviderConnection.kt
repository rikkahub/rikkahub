package me.rerere.ai.provider

/**
 * The outcome of testing a provider's connection, reduced to the four states the onboarding UI
 * actually distinguishes. The UI branches ONLY on this type — never on a raw HTTP status — so the
 * "is the key wrong / does the provider not list models / is the endpoint wrong" decision lives in
 * exactly one place ([classifyProviderConnection]) instead of being re-derived per provider.
 */
sealed interface ConnectionResult {
    /**
     * Reached the provider and retrieved its model list. [rateLimited] true means the list call was
     * throttled (HTTP 429), so the catalog couldn't be read right now and [modelCount] may be 0;
     * when [rateLimited] is false the invariant `modelCount > 0` holds.
     */
    data class Valid(val modelCount: Int, val rateLimited: Boolean = false) : ConnectionResult

    /** The API key was rejected (HTTP 401 / 403). */
    data class InvalidKey(val status: Int) : ConnectionResult

    /**
     * The server provably speaks the API (a chat call succeeded, or returned an authed
     * provider-error such as model-not-found) but exposes no usable model list — the user should
     * add models manually rather than fetch them.
     */
    data object ReachableNoModelList : ConnectionResult

    /**
     * No working API endpoint was found at the configured base URL: a network failure, a non-API
     * response (e.g. an HTML proxy page), a 5xx, or an unproven 4xx. The user should check the Base
     * URL / API Path (or add a model manually and re-test).
     */
    data object UnreachableOrWrongEndpoint : ConnectionResult
}

/**
 * The result of one HTTP probe — the `/models` endpoint, or an optional chat call — reduced to just
 * the facts [classifyProviderConnection] needs. [Transport] means no HTTP response arrived at all.
 */
sealed interface ProbeOutcome {
    /** An HTTP response arrived with this [status]; [body] is its parsed shape. */
    data class Http(val status: Int, val body: Body) : ProbeOutcome

    /** A network-layer failure — no HTTP status was ever received. */
    data class Transport(val error: TransportError) : ProbeOutcome

    /** What the response body was, once parsed in the probe's expected shape. */
    sealed interface Body {
        /** A provider-shaped model array parsed cleanly, with [count] entries. */
        data class ModelList(val count: Int) : Body

        /** A valid chat completion / response payload. */
        data object ChatOk : Body

        /** A provider-shaped JSON error object (e.g. invalid_request, model_not_found). */
        data object ProviderError : Body

        /** Body was JSON but not the shape this probe expected. */
        data object JsonWrongShape : Body

        /** Body wasn't JSON at all — an HTML proxy page, an empty body, or garbage. */
        data object NotJson : Body
    }

    /**
     * Network-layer failures, no HTTP status received. A `CancellationException` must be propagated
     * by the caller and never turned into a [Transport] — cancellation is not a connection verdict.
     */
    enum class TransportError { DNS, CONNECT, TIMEOUT, SSL, IO, OTHER }
}

/**
 * Classify a provider connection from the `/models` probe and an optional [chat] probe.
 *
 * [chat] is null when no model id was available to probe with — a brand-new provider before any
 * model is added; in that case we never invent a model id, so [ReachableNoModelList] (which needs a
 * chat proof) cannot be reached and an ambiguous `/models` failure resolves to
 * [ConnectionResult.UnreachableOrWrongEndpoint] (UI offers "Add manually" + re-test).
 *
 * Order matters: a proven-good model list wins over a stray chat auth failure, and auth rejection
 * on the list call wins over everything.
 */
fun classifyProviderConnection(models: ProbeOutcome, chat: ProbeOutcome? = null): ConnectionResult {
    if (models.isAuthRejection()) return ConnectionResult.InvalidKey(models.statusOr(401))

    val modelsBody = (models as? ProbeOutcome.Http)?.body
    if (modelsBody is ProbeOutcome.Body.ModelList && modelsBody.count > 0) {
        return ConnectionResult.Valid(modelsBody.count, rateLimited = chat.isRateLimited())
    }

    if (models is ProbeOutcome.Http && models.status == 429) {
        return ConnectionResult.Valid(modelCount = 0, rateLimited = true)
    }

    if (chat.isAuthRejection()) return ConnectionResult.InvalidKey(chat.statusOr(401))
    if (chat.provesApiReachable()) return ConnectionResult.ReachableNoModelList

    return ConnectionResult.UnreachableOrWrongEndpoint
}

private fun ProbeOutcome?.isAuthRejection(): Boolean =
    this is ProbeOutcome.Http && (status == 401 || status == 403)

private fun ProbeOutcome?.isRateLimited(): Boolean =
    this is ProbeOutcome.Http && status == 429

private fun ProbeOutcome?.statusOr(fallback: Int): Int =
    (this as? ProbeOutcome.Http)?.status ?: fallback

/**
 * A chat probe proves the server speaks the API when it returns a 2xx chat payload, or a 4xx
 * provider error (auth + endpoint are fine; only the probed model was invalid — the
 * 400-model-not-found case folds in here).
 *
 * Best-effort by design: a 4xx is taken as a provider error from any JSON error shape, so a generic
 * gateway error (e.g. `{"message":"Not Found"}`) can read as ReachableNoModelList rather than
 * UnreachableOrWrongEndpoint. Both verdicts steer the user to "add models manually", so the
 * mislabel is low-harm; distinguishing them would need per-provider model-not-found error codes (a
 * follow-up, not worth a heuristic that guesses wrong the other way). The probe only runs at all
 * when /models failed AND a real model id exists.
 */
private fun ProbeOutcome?.provesApiReachable(): Boolean {
    val http = this as? ProbeOutcome.Http ?: return false
    return when {
        http.status in 200..299 && http.body is ProbeOutcome.Body.ChatOk -> true
        http.status in 400..499 && http.body is ProbeOutcome.Body.ProviderError -> true
        else -> false
    }
}
